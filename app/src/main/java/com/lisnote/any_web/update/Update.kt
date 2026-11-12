package com.lisnote.any_web.update

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

internal fun update(
    context: Context,
    source: String,
    onAppReady: () -> Unit,
    onUnchanged: () -> Unit,
    onFailure: (Exception) -> Unit,
    onReady: (PreparedExtension) -> Unit
) {
    val app = context.applicationContext
    val mainHandler = Handler(Looper.getMainLooper())
    Thread {
        try {
            val connection = URL(source).openConnection().apply {
                connectTimeout = 15_000
                readTimeout = 30_000
            }
            val data = try {
                connection.getInputStream().bufferedReader(Charsets.UTF_8).use {
                    JSONObject(it.readText())
                }
            } finally {
                (connection as? HttpURLConnection)?.disconnect()
            }
            val appVersion = if (data.isNull("appVersion")) "" else data.getString("appVersion").trim()
            val installedVersion = app.packageManager.getPackageInfo(app.packageName, 0).versionName
            val webVersion = if (data.isNull("webVersion")) "" else data.getString("webVersion").trim()
            when {
                appVersion.isNotEmpty() && appVersion != installedVersion -> {
                    AppUpdate.perform(app, data)
                    mainHandler.post { onAppReady() }
                }
                WebUpdate.needsUpdate(app, webVersion) -> {
                    val prepared = WebUpdate.perform(app, data)
                    mainHandler.post { onReady(prepared) }
                }
                // 没有更新，不调用 AppUpdate/WebUpdate 的更新流程。
                else -> mainHandler.post { onUnchanged() }
            }
        } catch (e: Exception) {
            mainHandler.post { onFailure(e) }
        }
    }.start()
}
