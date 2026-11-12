package com.lisnote.any_web.update

import android.content.Context
import androidx.core.content.edit
import org.json.JSONObject
import java.io.File

internal object AppUpdate {
    // 由 Update 确认需要更新后，在同一个后台线程执行。
    fun perform(context: Context, data: JSONObject) {
        val version = data.getString("appVersion").trim()
        val app = context.applicationContext
        val prefs = app.getSharedPreferences("update", Context.MODE_PRIVATE)
        val destination = File(app.filesDir, "update.apk")
        val cached = prefs.getString("downloadedAppVersion", "") == version && destination.isFile
        if (!cached) {
            val source = data.optString("appSource").trim()
            check(source.isNotEmpty()) { "appSource 未提供" }
            app.showUpdateNotice("发现 app 新版本，开始更新")
            Downloader.download(source, destination)
            prefs.edit { putString("downloadedAppVersion", version) }
        }
        clearWeb(app)
        // 调用方在主线程打开系统安装界面，不把待安装版本当作已安装版本。
    }

    private fun clearWeb(context: Context) {
        val root = context.filesDir.canonicalFile
        for (name in listOf(
            "web.zip", "web.zip.bak", "web.zip.new", "html", "html.previous",
            "web-container.xpi", "ext.zip"
        )) {
            val target = File(root, name)
            check(target.canonicalPath.startsWith(root.path + File.separator)) { "清理路径越界" }
            check(!target.exists() || target.deleteRecursively()) { "无法清理 $name" }
        }
        context.getSharedPreferences("update", Context.MODE_PRIVATE).edit {
            remove("webVersion")
            remove("bundledExtFingerprint")
            remove("installedSourceFingerprint")
            remove("pendingSourceFingerprint")
            remove("pendingWebVersion")
            remove("pendingBundledExtFingerprint")
            remove("extVersion")
        }
    }

}
