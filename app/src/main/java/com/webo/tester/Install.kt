package com.webo.tester

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.FileInputStream

object Status {
    @Volatile var msg: String = ""
}

object Installer {
    fun install(ctx: Context, apk: File) {
        val pi = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        val id = pi.createSession(params)
        val session = pi.openSession(id)
        try {
            FileInputStream(apk).use { ins ->
                session.openWrite("webo.apk", 0, apk.length()).use { out ->
                    ins.copyTo(out)
                    session.fsync(out)
                }
            }
            val intent = Intent(ctx, InstallReceiver::class.java).setPackage(ctx.packageName)
            val pend = PendingIntent.getBroadcast(
                ctx, id, intent,
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            session.commit(pend.intentSender)
            Status.msg = "تم إرسال الملف للنظام — اضغط «تثبيت» في النافذة التي ستظهر"
        } catch (e: Exception) {
            session.abandon()
            Status.msg = "فشل بدء التثبيت: " + e.message
        } finally {
            session.close()
        }
    }

    fun saveToDownloads(ctx: Context, name: String, mime: String, data: ByteArray): String? {
        return try {
            val v = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/WeboTester")
            }
            val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v) ?: return null
            ctx.contentResolver.openOutputStream(uri)?.use { it.write(data) }
            "Download/WeboTester/" + name
        } catch (e: Exception) {
            null
        }
    }
}

class InstallReceiver : BroadcastReceiver() {
    @Suppress("DEPRECATION")
    override fun onReceive(c: Context, i: Intent) {
        when (i.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val it = i.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                if (it != null) {
                    it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    c.startActivity(it)
                }
            }
            PackageInstaller.STATUS_SUCCESS -> Status.msg = "تم تثبيت Webo بنجاح"
            else -> Status.msg = "فشل التثبيت: " + (i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "سبب غير معروف")
        }
    }
}
