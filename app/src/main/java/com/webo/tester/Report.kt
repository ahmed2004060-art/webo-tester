package com.webo.tester

import android.content.Context
import android.util.Base64
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Report {
    @Volatile var savedTo: String? = null

    private fun esc(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun b64(f: File): String =
        try { Base64.encodeToString(f.readBytes(), Base64.NO_WRAP) } catch (e: Exception) { "" }

    fun build(ctx: Context, ex: Explorer): File {
        val tf = SimpleDateFormat("HH:mm:ss", Locale.US)
        val shotsDir = File(ex.dir, "shots")
        val sb = StringBuilder()
        sb.append("<!doctype html><html lang=\"ar\" dir=\"rtl\"><head><meta charset=\"utf-8\">")
        sb.append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
        sb.append("<title>تقرير اختبار Webo</title><style>")
        sb.append("body{font:14px/1.6 system-ui,sans-serif;margin:0;padding:12px;background:#fff;color:#111}")
        sb.append("@media(prefers-color-scheme:dark){body{background:#14171b;color:#eceff3}td,th{border-color:#333a42!important}}")
        sb.append("h1{font-size:20px}h2{font-size:17px;margin-top:26px;border-bottom:2px solid #888;padding-bottom:4px}")
        sb.append("table{border-collapse:collapse;width:100%;font-size:12px}td,th{border:1px solid #ccc;padding:4px;vertical-align:top;word-break:break-word}")
        sb.append(".wrap{overflow-x:auto}.CRASH,.ANR,.ERR{background:#f8d7da;color:#600}.VANISH,.OFFAPP{background:#ffe8cc;color:#522}")
        sb.append(".slow{color:#c60;font-weight:700}.PERM{background:#e3f0ff;color:#014}.SCREEN{background:#e6f6e6;color:#030}")
        sb.append("img.t{width:90px;border-radius:4px}img.g{width:100%;max-width:200px;border-radius:6px}")
        sb.append(".g1{display:inline-block;width:46%;margin:1%;vertical-align:top;font-size:11px}.k{font-weight:700}")
        sb.append("</style></head><body>")

        sb.append("<h1>تقرير اختبار Webo</h1>")
        sb.append("<p>").append(esc(SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(ex.startT))))
            .append(" · المدة: ").append((ex.endT - ex.startT) / 1000).append(" ثانية · إصدار Webo: ")
            .append(esc(ex.weboVersion)).append("</p>")
        sb.append("<p>القسم الأول سجل حرفي لكل ما حدث كما هو. الملخص في آخر التقرير.</p>")

        // ---- 1. literal log
        sb.append("<h2>١. السجل الحرفي الكامل (").append(ex.events.size).append(" حدث)</h2><div class=\"wrap\"><table>")
        sb.append("<tr><th>#</th><th>الوقت</th><th>النوع</th><th>ما حدث</th><th>ms</th><th>لقطة</th></tr>")
        var embedded = 0
        for (e in ex.events) {
            val slow = e.ms > 2500
            sb.append("<tr class=\"").append(e.kind).append("\"><td>").append(e.n).append("</td><td>")
                .append(tf.format(Date(e.t))).append("</td><td>").append(e.kind).append("</td><td>")
                .append(esc(e.msg)).append("</td><td")
            if (slow) sb.append(" class=\"slow\"")
            sb.append(">").append(if (e.ms >= 0) e.ms.toString() else "").append("</td><td>")
            if (e.shot != null && embedded < 160) {
                val d = b64(File(shotsDir, e.shot))
                if (d.isNotEmpty()) {
                    sb.append("<img class=\"t\" src=\"data:image/jpeg;base64,").append(d).append("\">")
                    embedded++
                }
            }
            sb.append("</td></tr>")
        }
        sb.append("</table></div>")

        // ---- 2. screens
        sb.append("<h2>٢. الشاشات المكتشفة (").append(ex.screens.size).append(")</h2>")
        for (s in ex.screens.values) {
            sb.append("<div class=\"g1\"><div class=\"k\">#").append(s.idx).append(" · خطوة ").append(s.atStep).append("</div>")
            if (s.shot != null) {
                val d = b64(File(shotsDir, s.shot))
                if (d.isNotEmpty()) sb.append("<img class=\"g\" src=\"data:image/jpeg;base64,").append(d).append("\"><br>")
            }
            sb.append(esc(s.labels.take(10).joinToString(" · "))).append("<br>")
            sb.append(s.total).append(" عنصر تفاعلي</div>")
        }

        // ---- 3. summary (last)
        val acts = ex.events.filter { it.kind == "ACT" }
        sb.append("<h2>٣. الملخص (في النهاية)</h2><div class=\"wrap\"><table>")
        fun row(k: String, v: Any) { sb.append("<tr><td>").append(k).append("</td><td><b>").append(v).append("</b></td></tr>") }
        row("خطوات منفّذة", ex.tasksDone)
        row("شاشات مكتشفة", ex.screens.size)
        row("شاشات بقيت فيها عناصر لم تُجرَّب (بلغت حد الخطوات أو لم يمكن الوصول إليها)", ex.pendingCount() + ex.unreachableCount())
        row("انهيارات مؤكدة (نافذة النظام ذكرت توقف Webo)", ex.crashes)
        row("عدم استجابة مؤكد (ANR)", ex.anrs)
        row("اختفاء مفاجئ لـ Webo إلى الشاشة الرئيسية (انهيار محتمل)", ex.vanished)
        row("مرات خروج Webo أو ظهور نوافذ نظام غير متوقعة", ex.offApp)
        row("خطوات نُفِّذت دون أي تغيّر مرئي", ex.noChange)
        row("خطوات استغرقت أكثر من 2.5 ثانية", ex.slow)
        sb.append("</table></div>")

        val critical = ex.events.filter { it.kind == "CRASH" || it.kind == "ANR" || it.kind == "VANISH" }
        sb.append("<h3>الأحداث الحرجة</h3>")
        if (critical.isEmpty()) sb.append("<p>لم يُسجَّل أي انهيار أو عدم استجابة أو اختفاء مفاجئ.</p>")
        else {
            sb.append("<ul>")
            for (e in critical) sb.append("<li><b>#").append(e.n).append(" ").append(e.kind).append("</b>: ").append(esc(e.msg)).append("</li>")
            sb.append("</ul>")
        }

        val slowest = acts.sortedByDescending { it.ms }.take(10)
        sb.append("<h3>أبطأ 10 خطوات</h3><ol>")
        for (e in slowest) sb.append("<li>").append(e.ms).append(" ms — #").append(e.n).append(" ").append(esc(e.msg.take(140))).append("</li>")
        sb.append("</ol>")

        val failed = acts.filter { it.msg.contains("لم يُنفَّذ") }
        sb.append("<h3>خطوات رفض النظام أو العنصر تنفيذها (").append(failed.size).append(")</h3><ul>")
        for (e in failed.take(40)) sb.append("<li>#").append(e.n).append(" ").append(esc(e.msg.take(140))).append("</li>")
        sb.append("</ul>")

        sb.append("<h3>حدود هذا التقرير</h3><ul>")
        sb.append("<li>لا يقيس استهلاك الذاكرة أو البطارية أو حركة الشبكة: أندرويد لا يسمح لتطبيق عادي بقراءتها من تطبيق آخر.</li>")
        sb.append("<li>لا يعرف سبب الانهيار البرمجي، فقط وقوعه وما سبقه من خطوات.</li>")
        sb.append("<li>«لا تغيّر مرئي» ليس بالضرورة عيباً، والحكم عليه لك.</li>")
        sb.append("<li>المحتوى الذي يمنع النظام تصويره (مثل DRM) يظهر أسود أو بلا لقطة.</li>")
        sb.append("<li>صفحات الويب داخل Webo تُجرَّب بحد أقصى 6 عناصر لكل شاشة.</li>")
        sb.append("</ul></body></html>")

        val out = File(ex.dir, "report.html")
        out.writeText(sb.toString())
        val name = "webo-report-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(ex.startT)) + ".html"
        savedTo = Installer.saveToDownloads(ctx, name, "text/html", out.readBytes())
        try {
            val raw = File(ex.dir, "log.jsonl")
            if (raw.exists()) Installer.saveToDownloads(ctx, name.replace(".html", ".jsonl"), "application/json", raw.readBytes())
        } catch (e: Exception) {
        }
        return out
    }
}
