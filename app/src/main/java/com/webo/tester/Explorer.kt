package com.webo.tester

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.Display
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.FileWriter
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class Cfg(
    val urls: List<String>,
    val maxMinutes: Int,
    val maxSteps: Int = 3000,
    val perScreen: Int = 40,
    val stagnation: Int = 250
)

class Ev(val n: Int, val t: Long, val kind: String, val msg: String, val ms: Long, val shot: String?)

class ScreenInfo(
    val idx: Int,
    val sig: String,
    val pkg: String,
    val labels: List<String>,
    val total: Int,
    val shot: String?,
    val atStep: Int
)

class El(
    val node: AccessibilityNodeInfo,
    val key: String,
    val skey: String,
    val edit: Boolean,
    val click: Boolean,
    val long: Boolean,
    val scroll: Boolean,
    val label: String,
    val web: Boolean
)

class Explorer(private val svc: TesterService, private val cfg: Cfg) {

    companion object {
        @Volatile var cur: Explorer? = null
        const val TARGET = "com.webo"
    }

    @Volatile var stopFlag = false
    @Volatile var running = false
    @Volatile var status = ""
    @Volatile var savedPath: String? = null
    @Volatile var reportFile: File? = null

    val events = ArrayList<Ev>()
    val screens = LinkedHashMap<String, ScreenInfo>()
    var steps = 0
    var shots = 0
    var crashes = 0
    var anrs = 0
    var vanished = 0
    var offApp = 0
    var noChange = 0
    var slow = 0
    var tasksDone = 0
    var startT = 0L
    var endT = 0L
    var weboVersion = ""
    lateinit var dir: File

    private val tried = HashSet<String>()
    private val perScreen = HashMap<String, Int>()
    private val perScreenWeb = HashMap<String, Int>()
    private val parent = HashMap<String, Pair<String, String>>()
    private val pending = LinkedHashSet<String>()
    private val unreachable = HashSet<String>()
    private val recent = ArrayList<String>()
    private var homeSig = ""
    private var sinceNew = 0
    private var lastShotT = 0L
    private var logW: FileWriter? = null
    private val inputs: List<String> = cfg.urls + listOf(
        "بحث تجريبي",
        "",
        "<script>alert(1)</script>",
        "javascript:alert(1)",
        "x".repeat(3000)
    )

    private fun now() = System.currentTimeMillis()

    fun stop() {
        stopFlag = true
    }

    fun begin() {
        if (running) return
        running = true
        cur = this
        Thread {
            try {
                runAll()
            } catch (e: Throwable) {
                log("ERR", "استثناء داخل المختبِر نفسه: " + e)
            } finally {
                finish()
            }
        }.start()
    }

    // ---------------------------------------------------------------- log
    @Synchronized
    fun log(kind: String, msg: String, ms: Long = -1, shot: String? = null) {
        val e = Ev(events.size + 1, now(), kind, msg, ms, shot)
        events.add(e)
        status = "خطوات: $steps · شاشات: ${screens.size} · آخر حدث: " + msg.take(80)
        try {
            val o = JSONObject()
            o.put("n", e.n); o.put("t", e.t); o.put("kind", kind)
            o.put("msg", msg); o.put("ms", ms); o.put("shot", shot ?: "")
            logW?.write(o.toString() + "\n")
            logW?.flush()
        } catch (x: Exception) {
        }
    }

    private fun remember(s: String) {
        recent.add(s)
        if (recent.size > 5) recent.removeAt(0)
    }

    // ---------------------------------------------------------------- basics
    private fun sleep(ms: Long) {
        try { Thread.sleep(ms) } catch (e: InterruptedException) {}
    }

    private fun root(): AccessibilityNodeInfo? = try { svc.rootInActiveWindow } catch (e: Exception) { null }

    private fun pkgOf(r: AccessibilityNodeInfo?): String = r?.packageName?.toString() ?: ""

    private fun back() {
        svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
        sleep(550)
    }

    private fun tap(x: Float, y: Float, dur: Long): Boolean {
        val p = Path().apply { moveTo(x, y) }
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(p, 0, dur)).build()
        val latch = CountDownLatch(1)
        var ok = false
        svc.dispatchGesture(g, object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(d: GestureDescription?) { ok = true; latch.countDown() }
            override fun onCancelled(d: GestureDescription?) { latch.countDown() }
        }, null)
        latch.await(2, TimeUnit.SECONDS)
        return ok
    }

    // ---------------------------------------------------------------- tree
    private fun collect(r: AccessibilityNodeInfo): List<El> {
        val out = ArrayList<El>()
        var count = 0
        fun walk(n: AccessibilityNodeInfo?, depth: Int, inWeb: Boolean) {
            if (n == null || depth > 45 || count > 1500) return
            count++
            val cls = n.className?.toString() ?: ""
            if (n.isVisibleToUser) {
                val ed = n.isEditable
                val cl = n.isClickable
                val lg = n.isLongClickable
                val sc = n.isScrollable
                if (ed || cl || lg || sc) {
                    val b = Rect()
                    n.getBoundsInScreen(b)
                    if (b.width() > 0 && b.height() > 0) {
                        val txt = (n.text?.toString() ?: "").take(30)
                        val desc = (n.contentDescription?.toString() ?: "").take(30)
                        val vid = n.viewIdResourceName ?: ""
                        val key = "$cls|$vid|$txt|$desc|${b.left / 24},${b.top / 24}"
                        val skey = "$cls|$vid|${b.left / 48},${b.top / 48},${b.right / 48},${b.bottom / 48}"
                        val label = when {
                            txt.isNotBlank() -> txt
                            desc.isNotBlank() -> desc
                            vid.isNotBlank() -> vid.substringAfterLast('/')
                            else -> cls.substringAfterLast('.') + "@" + b.centerX() + "," + b.centerY()
                        }
                        out.add(El(n, key, skey, ed, cl, lg, sc, label, inWeb))
                    }
                }
            }
            val childWeb = inWeb || cls == "android.webkit.WebView"
            for (i in 0 until n.childCount) walk(n.getChild(i), depth + 1, childWeb)
        }
        walk(r, 0, false)
        return out
    }

    private fun sigOf(p: String, els: List<El>): String {
        val b = StringBuilder(p)
        for (e in els) if (!e.web) b.append('|').append(e.skey)
        val webs = els.filter { it.web }
        b.append("|w").append(minOf(webs.size, 3))
        for (e in webs.take(2)) b.append('|').append(e.label)
        return p.substringAfterLast('.') + "#" + b.toString().hashCode().toString(16) + "-" + b.length
    }

    private fun sigNow(): String {
        val r = root() ?: return "null"
        val p = pkgOf(r)
        if (p != TARGET) return "pkg:$p"
        return sigOf(p, collect(r))
    }

    private fun settle(maxMs: Long): Long {
        val t0 = now()
        var last = sigNow()
        var stableSince = now()
        while (now() - t0 < maxMs) {
            sleep(150)
            val s = sigNow()
            if (s != last) { last = s; stableSince = now() }
            else if (now() - stableSince >= 450) break
        }
        return stableSince - t0
    }

    private fun texts(r: AccessibilityNodeInfo): List<String> {
        val o = ArrayList<String>()
        fun w(n: AccessibilityNodeInfo?, d: Int) {
            if (n == null || d > 30) return
            n.text?.toString()?.let { if (it.isNotBlank()) o.add(it) }
            n.contentDescription?.toString()?.let { if (it.isNotBlank()) o.add(it) }
            for (i in 0 until n.childCount) w(n.getChild(i), d + 1)
        }
        w(r, 0)
        return o.distinct()
    }

    private fun clickAny(r: AccessibilityNodeInfo, words: List<String>): String? {
        for (w in words) {
            val list = r.findAccessibilityNodeInfosByText(w) ?: continue
            for (n in list) {
                var x: AccessibilityNodeInfo? = n
                var d = 0
                while (x != null && !x.isClickable && d < 4) { x = x.parent; d++ }
                if (x != null && x.isClickable && x.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return w
            }
        }
        return null
    }

    // ---------------------------------------------------------------- screenshot
    private fun shot(): String? {
        if (shots >= 250) return null
        val wait = 1150 - (now() - lastShotT)
        if (wait > 0) sleep(wait)
        val latch = CountDownLatch(1)
        var name: String? = null
        try {
            svc.takeScreenshot(Display.DEFAULT_DISPLAY, svc.mainExecutor,
                object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(r: AccessibilityService.ScreenshotResult) {
                        try {
                            val hb = r.hardwareBuffer
                            val bmp = Bitmap.wrapHardwareBuffer(hb, r.colorSpace)
                            if (bmp != null) {
                                val sw = bmp.copy(Bitmap.Config.ARGB_8888, false)
                                val w = 360
                                val h = sw.height * w / sw.width
                                val sc = Bitmap.createScaledBitmap(sw, w, h, true)
                                shots++
                                val f = "s$shots.jpg"
                                FileOutputStream(File(dir, "shots/$f")).use {
                                    sc.compress(Bitmap.CompressFormat.JPEG, 45, it)
                                }
                                name = f
                            }
                            hb.close()
                        } catch (x: Throwable) {
                        }
                        latch.countDown()
                    }

                    override fun onFailure(code: Int) { latch.countDown() }
                })
            latch.await(4, TimeUnit.SECONDS)
        } catch (x: Throwable) {
        }
        lastShotT = now()
        return name
    }

    // ---------------------------------------------------------------- launch
    private fun relaunch(reason: String) {
        log("RELAUNCH", "إعادة فتح Webo: $reason")
        try {
            val i = svc.packageManager.getLaunchIntentForPackage(TARGET)
            if (i != null) {
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                svc.startActivity(i)
            }
        } catch (e: Exception) {
        }
        sleep(2500)
        if (pkgOf(root()) == TARGET) return
        svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
        sleep(1300)
        val r = root()
        if (r != null) {
            val ok = clickAny(r, listOf("WEBO", "Webo"))
            log("RELAUNCH", "محاولة فتحه من أيقونة الشاشة الرئيسية: " + (ok ?: "لم أجد الأيقونة"))
        }
        sleep(2500)
        if (pkgOf(root()) != TARGET) {
            log("ERR", "تعذّرت إعادة فتح Webo، توقف الاختبار")
            stopFlag = true
        }
    }

    private fun restartApp(reason: String) {
        var i = 0
        while (i < 6 && pkgOf(root()) == TARGET) { back(); i++ }
        relaunch(reason)
        settle(5000)
    }

    // ---------------------------------------------------------------- system windows
    private fun handleSystem(r: AccessibilityNodeInfo, p: String) {
        val tx = texts(r)
        val joined = tx.joinToString(" | ")
        val low = joined.lowercase(Locale.ROOT)
        val mentionsWebo = low.contains("webo")
        val isAnr = low.contains("isn't responding") || low.contains("not responding") ||
            low.contains("لا يستجيب") || low.contains("لا يستجب")
        val isCrash = low.contains("keeps stopping") || low.contains("has stopped") ||
            low.contains("يتوقف") || low.contains("توقف")
        val ctx = "آخر خطوات قبله: " + recent.joinToString(" ← ")
        if ((isAnr || isCrash) && mentionsWebo) {
            val s = shot()
            if (isAnr) { anrs++; log("ANR", "نافذة النظام: Webo لا يستجيب — «$joined». $ctx", -1, s) }
            else { crashes++; log("CRASH", "نافذة النظام: Webo توقف — «$joined». $ctx", -1, s) }
            val c = clickAny(r, listOf("Close app", "إغلاق التطبيق", "OK", "حسنًا", "حسناً", "موافق", "Close"))
            if (c == null) back()
            sleep(800)
            return
        }
        if (p.contains("permissioncontroller") || p.contains("packageinstaller")) {
            val s = shot()
            val c = clickAny(r, listOf("While using the app", "أثناء استخدام التطبيق", "Only this time",
                "هذه المرة فقط", "Allow", "السماح"))
            log("PERM", "نافذة أذونات: «$joined» ← ضغط: ${c ?: "لا شيء، رجوع"}", -1, s)
            if (c == null) back()
            sleep(700)
            return
        }
        offApp++
        log("OFFAPP", "نافذة نظام ظهرت ($p): «${joined.take(200)}» ← رجوع")
        back()
    }

    // ---------------------------------------------------------------- tasks
    private class Task(val el: El, val mode: String, val id: String)

    private fun modesOf(e: El): List<String> {
        if (e.edit) return inputs.indices.map { "T$it" }
        val m = ArrayList<String>()
        if (e.click) m.add("C")
        if (e.long && !e.web) m.add("L")
        if (e.scroll) m.add("S")
        return m
    }

    private fun pickNext(sig: String, els: List<El>): Task? {
        if ((perScreen[sig] ?: 0) >= cfg.perScreen) return null
        val webDone = perScreenWeb[sig] ?: 0
        for (e in els) {
            for (m in modesOf(e)) {
                val id = "$sig#${e.key}#$m"
                if (id in tried) continue
                if (e.web && webDone >= 6) continue
                return Task(e, m, id)
            }
        }
        return null
    }

    private fun hideKeyboard() {
        try {
            if (svc.windows.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }) back()
        } catch (e: Exception) {
        }
    }

    private fun doTask(sig: String, t: Task) {
        val el = t.el
        tried.add(t.id)
        perScreen[sig] = (perScreen[sig] ?: 0) + 1
        if (el.web) perScreenWeb[sig] = (perScreenWeb[sig] ?: 0) + 1
        steps++
        sinceNew++
        tasksDone++
        val t0 = now()
        var ok = false
        var what = ""
        try {
            when {
                t.mode == "C" -> {
                    what = "نقر"
                    ok = el.node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    if (!ok) {
                        val b = Rect(); el.node.getBoundsInScreen(b)
                        ok = tap(b.exactCenterX(), b.exactCenterY(), 60)
                        what = "نقر (بالإحداثيات)"
                    }
                }
                t.mode == "L" -> {
                    what = "ضغط مطوّل"
                    ok = el.node.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)
                }
                t.mode == "S" -> {
                    what = "تمرير"
                    for (i in 1..3) { ok = el.node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) or ok; sleep(350) }
                    for (i in 1..3) { el.node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD); sleep(250) }
                }
                else -> {
                    val txt = inputs[t.mode.substring(1).toInt()]
                    what = "كتابة «" + txt.take(40) + "»" + (if (txt.length > 40) "…(${txt.length} حرف)" else "")
                    el.node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                    val b = Bundle()
                    b.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, txt)
                    ok = el.node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, b)
                    sleep(300)
                    el.node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
                }
            }
        } catch (e: Exception) {
            log("ERR", "استثناء أثناء تنفيذ $what على «${el.label}»: $e")
        }
        val callMs = now() - t0
        val settleMs = settle(if (t.mode.startsWith("T")) 8000 else 4000)
        hideKeyboard()
        val total = callMs + settleMs

        val r2 = root()
        val p2 = pkgOf(r2)
        var changed = false
        var note = ""
        if (r2 != null && p2 == TARGET) {
            val ns = sigOf(p2, collect(r2))
            changed = ns != sig
            if (changed && t.mode == "C" && !screens.containsKey(ns) && !parent.containsKey(ns) && ns != homeSig) {
                parent[ns] = Pair(sig, el.key)
            }
        } else {
            note = " — بعد الخطوة ظهرت حزمة: " + (if (p2.isEmpty()) "غير معروفة" else p2)
            val launcherLike = p2.contains("launcher") || p2.contains("home")
            if (launcherLike) {
                vanished++
                log("VANISH", "اختفى Webo بعد: $what على «${el.label}» (انهيار محتمل أو خروج مقصود). آخر خطوات: " +
                    recent.joinToString(" ← "))
            }
        }
        if (total > 2500) slow++
        if (ok && !changed && p2 == TARGET) noChange++
        val line = "$what على «${el.label}» ← ${if (ok) "نُفِّذ" else "لم يُنفَّذ"}؛ " +
            (if (changed) "تغيّرت الشاشة" else "لا تغيّر مرئي") + note
        remember(line.take(70))
        log("ACT", line, total)
    }

    // ---------------------------------------------------------------- navigation by path replay
    private fun replayTo(target: String): Boolean {
        val path = ArrayList<Pair<String, String>>()
        var c = target
        var guard = 0
        while (parent.containsKey(c) && guard < 30) {
            val pr = parent[c]!!
            path.add(0, pr)
            c = pr.first
            guard++
        }
        if (path.isEmpty()) {
            log("NAV", "لا مسار معروف للوصول إلى الشاشة #${screens[target]?.idx} — تخطّيتها")
            return false
        }
        restartApp("للعودة إلى الشاشة #${screens[target]?.idx}")
        if (stopFlag) return false
        for ((_, key) in path) {
            val r = root() ?: return false
            if (pkgOf(r) != TARGET) return false
            val e = collect(r).firstOrNull { it.key == key }
            if (e == null) {
                log("NAV", "تعذّر إيجاد العنصر في طريق العودة إلى الشاشة #${screens[target]?.idx}")
                return false
            }
            e.node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            settle(4000)
        }
        val ok = sigNow() == target
        log("NAV", "العودة إلى الشاشة #${screens[target]?.idx}: " + (if (ok) "نجحت" else "وصلت لشاشة مختلفة"))
        return true
    }

    // ---------------------------------------------------------------- url check
    private fun checkUrls() {
        for (u in cfg.urls) {
            val t0 = now()
            try {
                val c = URL(u).openConnection() as HttpURLConnection
                c.connectTimeout = 8000
                c.readTimeout = 8000
                c.setRequestProperty("Range", "bytes=0-0")
                val code = c.responseCode
                c.disconnect()
                log("URLCHK", "رابط الاختبار $u ← استجابة HTTP $code", now() - t0)
            } catch (e: Exception) {
                log("URLCHK", "رابط الاختبار $u ← تعذّر الاتصال: " + e.javaClass.simpleName)
            }
        }
    }

    // ---------------------------------------------------------------- main
    private fun runAll() {
        startT = now()
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        dir = File(svc.filesDir, "runs/$ts")
        File(dir, "shots").mkdirs()
        logW = FileWriter(File(dir, "log.jsonl"))
        weboVersion = try { svc.packageManager.getPackageInfo(TARGET, 0).versionName ?: "؟" } catch (e: Exception) { "غير مثبّت" }
        log("START", "بدء الاختبار — الجهاز: ${Build.MANUFACTURER} ${Build.MODEL}، أندرويد ${Build.VERSION.RELEASE} " +
            "(API ${Build.VERSION.SDK_INT})، إصدار Webo: $weboVersion")
        checkUrls()
        if (pkgOf(root()) != TARGET) relaunch("بداية التشغيل")
        settle(5000)

        val deadline = startT + cfg.maxMinutes * 60000L
        var nullCount = 0
        var offStreak = 0
        var exhausted = 0
        var endReason = ""

        while (!stopFlag) {
            if (steps >= cfg.maxSteps) { endReason = "بلغ الحد الأقصى للخطوات (${cfg.maxSteps})"; break }
            if (now() > deadline) { endReason = "انتهت المدة المحددة (${cfg.maxMinutes} دقيقة)"; break }
            if (sinceNew >= cfg.stagnation) { endReason = "لم تظهر شاشة جديدة خلال ${cfg.stagnation} خطوة"; break }

            val r = root()
            if (r == null) {
                sleep(500)
                nullCount++
                if (nullCount > 10) { nullCount = 0; log("ERR", "لا توجد نافذة نشطة قابلة للقراءة"); relaunch("نافذة غير قابلة للقراءة") }
                continue
            }
            nullCount = 0
            val p = pkgOf(r)
            if (p == "android" || p.contains("permissioncontroller") || p.contains("packageinstaller")) {
                handleSystem(r, p)
                continue
            }
            if (p != TARGET) {
                offStreak++
                offApp++
                log("OFFAPP", "خرج التطبيق إلى: $p (محاولة رقم $offStreak)")
                if (offStreak <= 2) back() else { restartApp("بعد الخروج من Webo إلى $p"); offStreak = 0 }
                continue
            }
            offStreak = 0

            val els = collect(r)
            val sig = sigOf(p, els)
            if (!screens.containsKey(sig)) {
                val labels = els.map { it.label }.filter { it.isNotBlank() }.distinct().take(30)
                val s = shot()
                screens[sig] = ScreenInfo(screens.size + 1, sig, p, labels, els.size, s, steps)
                sinceNew = 0
                if (homeSig.isEmpty()) homeSig = sig
                log("SCREEN", "شاشة جديدة #${screens.size}: " + labels.take(8).joinToString(" · ") +
                    " (${els.size} عنصر تفاعلي)", -1, s)
            }

            val next = pickNext(sig, els)
            if (next != null) {
                pending.add(sig)
                exhausted = 0
                doTask(sig, next)
                continue
            }
            pending.remove(sig)

            if (pending.isEmpty()) {
                exhausted++
                if (exhausted >= 3) { endReason = "اكتمل استكشاف كل ما أمكن الوصول إليه"; break }
                back()
                continue
            }
            back()
            settle(2500)
            if (sigNow() in pending) continue
            val tgt = pending.filter { it !in unreachable }.maxByOrNull { screens[it]?.idx ?: 0 }
            if (tgt == null) { endReason = "لا شاشات متبقية يمكن الوصول إليها"; break }
            if (!replayTo(tgt)) { unreachable.add(tgt); pending.remove(tgt) }
        }
        if (stopFlag && endReason.isEmpty()) endReason = "أوقفه المستخدم أو تعذّر المتابعة"
        log("END", endReason)
    }

    private fun finish() {
        endT = now()
        try { logW?.close() } catch (e: Exception) {}
        try {
            val f = Report.build(svc, this)
            reportFile = f
            savedPath = Report.savedTo
            status = "انتهى — التقرير محفوظ" + (if (savedPath != null) " في $savedPath" else "")
        } catch (e: Throwable) {
            status = "انتهى لكن فشل إنشاء التقرير: $e"
        }
        running = false
    }

    fun unreachableCount() = unreachable.size
    fun pendingCount() = pending.size
}
