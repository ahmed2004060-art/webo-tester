package com.webo.tester

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : Activity() {

    private lateinit var uploadWeb: WebView
    private lateinit var reportWeb: WebView
    private lateinit var panel: ScrollView
    private lateinit var setupTv: TextView
    private lateinit var liveTv: TextView
    private lateinit var repoEt: EditText
    private lateinit var urlsEt: EditText
    private lateinit var minEt: EditText
    private var fileCb: ValueCallback<Array<Uri>>? = null
    private val ui = Handler(Looper.getMainLooper())
    private val FILE_REQ = 77

    private val defaultUrls = listOf(
        "https://download.blender.org/peach/bigbuckbunny_movies/BigBuckBunny_320x180.mp4",
        "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8",
        "https://storage.googleapis.com/shaka-demo-assets/angel-one/dash.mpd",
        "https://example.com"
    )

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun btn(t: String, f: () -> Unit): Button {
        val b = Button(this)
        b.text = t
        b.textSize = 14f
        b.setOnClickListener { f() }
        return b
    }

    private fun tv(t: String, size: Float = 14f): TextView {
        val v = TextView(this)
        v.text = t
        v.textSize = size
        v.setPadding(dp(4), dp(6), dp(4), dp(2))
        return v
    }

    inner class Bridge {
        @JavascriptInterface
        fun onBuilt(url: String) {
            ui.post {
                // https://github.com/user/repo/releases/download/...
                try {
                    val parts = Uri.parse(url).pathSegments
                    if (parts.size >= 2) repoEt.setText(parts[0] + "/" + parts[1])
                } catch (e: Exception) {
                }
                show(1)
                downloadAndInstall(url)
            }
        }
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(Color.WHITE)

        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        val names = listOf("رفع وبناء", "اختبار", "التقرير")
        for ((i, n) in names.withIndex()) {
            val bt = btn(n) { show(i) }
            bar.addView(bt, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        root.addView(bar)

        val frame = FrameLayout(this)
        root.addView(frame, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // --- uploader
        uploadWeb = WebView(this)
        setupWeb(uploadWeb)
        uploadWeb.addJavascriptInterface(Bridge(), "WeboTester")
        uploadWeb.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                w: WebView?, cb: ValueCallback<Array<Uri>>?, p: FileChooserParams?
            ): Boolean {
                fileCb?.onReceiveValue(null)
                fileCb = cb
                val i = Intent(Intent.ACTION_GET_CONTENT)
                i.addCategory(Intent.CATEGORY_OPENABLE)
                i.type = "*/*"
                startActivityForResult(Intent.createChooser(i, "اختر ملف zip"), FILE_REQ)
                return true
            }
        }
        val html = assets.open("uploader.html").bufferedReader().use { it.readText() }
        uploadWeb.loadDataWithBaseURL("https://appassets.local/", html, "text/html", "utf-8", null)
        frame.addView(uploadWeb)

        // --- test panel
        panel = ScrollView(this)
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(12), dp(8), dp(12), dp(24))
        panel.addView(col)

        setupTv = tv("", 14f)
        col.addView(setupTv)

        col.addView(btn("١) السماح بتثبيت التطبيقات") {
            startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
        })
        col.addView(btn("٢) تفعيل خدمة الوصول (WEBO Tester)") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })
        col.addView(tv("اسم الحساب/المستودع على GitHub (مثال: ali/webo)", 12f))
        repoEt = EditText(this)
        repoEt.setSingleLine()
        repoEt.textDirection = View.TEXT_DIRECTION_LTR
        repoEt.setText(getSharedPreferences("p", Context.MODE_PRIVATE).getString("repo", ""))
        col.addView(repoEt)
        col.addView(btn("٣) تنزيل وتثبيت آخر نسخة من Webo") { fetchLatest() })

        col.addView(tv("روابط الاختبار (سطر لكل رابط):", 12f))
        urlsEt = EditText(this)
        urlsEt.textDirection = View.TEXT_DIRECTION_LTR
        urlsEt.gravity = Gravity.START
        urlsEt.setText(defaultUrls.joinToString("\n"))
        col.addView(urlsEt)
        col.addView(tv("المدة القصوى بالدقائق:", 12f))
        minEt = EditText(this)
        minEt.setSingleLine()
        minEt.setText("40")
        minEt.inputType = android.text.InputType.TYPE_CLASS_NUMBER
        col.addView(minEt)

        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.addView(btn("▶ بدء الاختبار") { startTest() }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(btn("■ إيقاف") { Explorer.cur?.stop() }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        col.addView(row)
        liveTv = tv("", 13f)
        col.addView(liveTv)
        frame.addView(panel)

        // --- report
        reportWeb = WebView(this)
        setupWeb(reportWeb)
        frame.addView(reportWeb)

        setContentView(root)
        show(0)
        tick()
    }

    private fun setupWeb(w: WebView) {
        val s = w.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.allowFileAccess = true
        s.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        w.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(v: WebView?, r: WebResourceRequest?): Boolean {
                val u = r?.url ?: return false
                if (u.scheme == "file" || u.host == "appassets.local") return false
                try { startActivity(Intent(Intent.ACTION_VIEW, u)) } catch (e: Exception) {}
                return true
            }
        }
    }

    private fun show(i: Int) {
        uploadWeb.visibility = if (i == 0) View.VISIBLE else View.GONE
        panel.visibility = if (i == 1) View.VISIBLE else View.GONE
        reportWeb.visibility = if (i == 2) View.VISIBLE else View.GONE
        if (i == 2) loadReport()
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        if (req == FILE_REQ) {
            fileCb?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(res, data))
            fileCb = null
        } else super.onActivityResult(req, res, data)
    }

    // ------------------------------------------------------------ status
    private fun weboVersion(): String? = try {
        packageManager.getPackageInfo("com.webo", 0).versionName ?: "؟"
    } catch (e: Exception) { null }

    private fun tick() {
        val canInstall = packageManager.canRequestPackageInstalls()
        val wv = weboVersion()
        val svc = TesterService.inst != null
        setupTv.text = "${if (canInstall) "✓" else "✗"} السماح بتثبيت التطبيقات\n" +
            "${if (wv != null) "✓ Webo مثبّت (الإصدار $wv)" else "✗ Webo غير مثبّت"}\n" +
            "${if (svc) "✓" else "✗"} خدمة الوصول مفعّلة\n" +
            (if (Status.msg.isNotEmpty()) "\n" + Status.msg else "")
        val ex = Explorer.cur
        liveTv.text = if (ex == null) "" else
            (if (ex.running) "قيد التشغيل — " else "") + ex.status +
                (if (ex.savedPath != null) "\nحُفظ التقرير في: " + ex.savedPath else "")
        ui.postDelayed({ tick() }, 1000)
    }

    // ------------------------------------------------------------ install flow
    private fun fetchLatest() {
        val repo = repoEt.text.toString().trim()
        if (!repo.contains("/")) {
            Toast.makeText(this, "اكتب الحساب/المستودع أولاً (مثال: ali/webo)", Toast.LENGTH_LONG).show()
            return
        }
        getSharedPreferences("p", Context.MODE_PRIVATE).edit().putString("repo", repo).apply()
        if (!packageManager.canRequestPackageInstalls()) {
            Toast.makeText(this, "اضغط الزر ١ أولاً للسماح بالتثبيت", Toast.LENGTH_LONG).show()
            return
        }
        Status.msg = "جاري البحث عن آخر نسخة..."
        Thread {
            try {
                val c = URL("https://api.github.com/repos/$repo/releases/latest").openConnection() as HttpURLConnection
                c.setRequestProperty("Accept", "application/vnd.github+json")
                c.connectTimeout = 15000
                c.readTimeout = 20000
                if (c.responseCode != 200) throw Exception("GitHub أجاب " + c.responseCode)
                val j = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
                val a = j.getJSONArray("assets")
                var url: String? = null
                for (i in 0 until a.length()) {
                    val o = a.getJSONObject(i)
                    if (o.getString("name").endsWith(".apk")) url = o.getString("browser_download_url")
                }
                val finalUrl: String = url
                    ?: throw Exception("لا يوجد ملف APK في آخر إصدار (ارفع عبر تبويب «رفع وبناء» ليُبنى بالشكل الصحيح)")
                ui.post { downloadAndInstall(finalUrl) }
            } catch (e: Exception) {
                Status.msg = "تعذّر جلب آخر نسخة: " + e.message
            }
        }.start()
    }

    private fun downloadAndInstall(url: String) {
        if (!packageManager.canRequestPackageInstalls()) {
            Status.msg = "اضغط الزر ١ للسماح بالتثبيت ثم أعد المحاولة"
            return
        }
        Status.msg = "جاري تنزيل Webo..."
        Thread {
            try {
                val c = URL(url).openConnection() as HttpURLConnection
                c.instanceFollowRedirects = true
                c.connectTimeout = 15000
                c.readTimeout = 30000
                if (c.responseCode != 200) throw Exception("HTTP " + c.responseCode)
                val f = File(cacheDir, "webo.apk")
                c.inputStream.use { i -> f.outputStream().use { o -> i.copyTo(o) } }
                ui.post { Installer.install(this, f) }
            } catch (e: Exception) {
                Status.msg = "فشل التنزيل: " + e.message
            }
        }.start()
    }

    // ------------------------------------------------------------ test
    private fun startTest() {
        if (TesterService.inst == null) {
            Toast.makeText(this, "فعّل خدمة الوصول أولاً (الزر ٢)", Toast.LENGTH_LONG).show()
            return
        }
        if (weboVersion() == null) {
            Toast.makeText(this, "ثبّت Webo أولاً (الزر ٣)", Toast.LENGTH_LONG).show()
            return
        }
        if (Explorer.cur?.running == true) return
        val urls = urlsEt.text.toString().lines().map { it.trim() }.filter { it.isNotEmpty() }
        val mins = minEt.text.toString().toIntOrNull() ?: 40
        val cfg = Cfg(urls, mins)
        val i = packageManager.getLaunchIntentForPackage("com.webo")
        if (i != null) startActivity(i)
        Toast.makeText(this, "بدأ الاختبار. اترك الهاتف ولا تلمس الشاشة. سيُحفظ التقرير تلقائياً.", Toast.LENGTH_LONG).show()
        ui.postDelayed({ TesterService.inst?.start(cfg) }, 2500)
    }

    private fun loadReport() {
        var f = Explorer.cur?.reportFile
        if (f == null || !f.exists()) {
            val runs = File(filesDir, "runs").listFiles()?.sortedByDescending { it.name }
            f = runs?.map { File(it, "report.html") }?.firstOrNull { it.exists() }
        }
        if (f == null) reportWeb.loadData("<html dir='rtl'><body style='font:16px sans-serif;padding:16px'>لا يوجد تقرير بعد.</body></html>", "text/html", "utf-8")
        else reportWeb.loadUrl("file://" + f.absolutePath)
    }
}
