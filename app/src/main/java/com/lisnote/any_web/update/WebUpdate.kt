package com.lisnote.any_web.update

import android.content.Context
import androidx.core.content.edit
import org.json.JSONObject
import java.io.File

internal object WebUpdate {
    fun needsUpdate(context: Context, version: String): Boolean {
        val prefs = context.getSharedPreferences("update", Context.MODE_PRIVATE)
        return (version.isNotEmpty() && version != prefs.getString("webVersion", "")) ||
            !File(context.filesDir, "web.zip").isFile ||
            !File(context.filesDir, "html").isDirectory ||
            // 修复旧逻辑把网页解压到插件根目录留下的缓存。
            File(context.filesDir, "html/index.html").isFile ||
            !File(context.filesDir, "web-container.xpi").isFile ||
            BundledExtension.fingerprint(context) != prefs.getString("bundledExtFingerprint", "")
    }

    fun perform(context: Context, data: JSONObject): PreparedExtension {
        val prefs = context.getSharedPreferences("update", Context.MODE_PRIVATE)
        val version = if (data.isNull("webVersion")) "" else data.getString("webVersion").trim()
        val zip = File(context.filesDir, "web.zip")
        if ((version.isNotEmpty() && version != prefs.getString("webVersion", "")) || !zip.isFile) {
            val source = if (data.isNull("webSource")) "" else data.getString("webSource").trim()
            check(source.isNotEmpty()) { "webSource 未提供" }
            context.showUpdateNotice("发现 web 新版本，开始更新")
            Downloader.download(source, zip)
        }
        val versions = mutableMapOf("bundledExtFingerprint" to BundledExtension.fingerprint(context))
        if (version.isNotEmpty()) versions["webVersion"] = version
        val prepared = ExtensionPackage.prepare(context.filesDir, true, versions) {
            BundledExtension.copyTo(context, it)
        }
        // 只保存待生效信息；已安装版本留到下次启动安装成功后更新。
        prefs.edit(commit = true) {
            putString("pendingSourceFingerprint", prepared.fingerprint)
            putString("pendingWebVersion", versions["webVersion"] ?: prefs.getString("webVersion", ""))
            putString("pendingBundledExtFingerprint", versions.getValue("bundledExtFingerprint"))
        }
        return prepared
    }

    fun cached(context: Context): PreparedExtension {
        val prepared = ExtensionPackage.prepare(context.filesDir, false, emptyMap())
        val prefs = context.getSharedPreferences("update", Context.MODE_PRIVATE)
        if (prefs.getString("pendingSourceFingerprint", "") != prepared.fingerprint) return prepared
        val versions = mutableMapOf<String, String>()
        prefs.getString("pendingWebVersion", "")?.takeIf { it.isNotEmpty() }?.let {
            versions["webVersion"] = it
        }
        prefs.getString("pendingBundledExtFingerprint", "")?.takeIf { it.isNotEmpty() }?.let {
            versions["bundledExtFingerprint"] = it
        }
        return prepared.copy(versions = versions)
    }
}
