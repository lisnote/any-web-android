package com.lisnote.any_web

import android.os.Bundle
import android.net.Uri
import android.content.Intent
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.content.edit
import androidx.core.content.FileProvider
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.WebExtension
import org.mozilla.geckoview.WebExtensionController
import org.mozilla.geckoview.WebRequestError
import org.mozilla.geckoview.StorageController
import android.util.Log
import java.io.File
import org.json.JSONObject
import com.lisnote.any_web.update.PreparedExtension
import com.lisnote.any_web.update.WebUpdate
import com.lisnote.any_web.update.update


class MainActivity : AppCompatActivity() {
    private lateinit var session: GeckoSession
    private var showingDefaultPage = false
    private var fallbackUrl: String? = null
    private var urlAttempted = false
    private var replacingSource = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        enableEdgeToEdge()
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
        try {
            val appConfig = try {
                assets.open("config.json").bufferedReader(Charsets.UTF_8).use {
                    JSONObject(it.readText())
                }
            } catch (_: Exception) {
                JSONObject()
            }
            val url = if (appConfig.isNull("url")) "" else appConfig.getString("url").trim()
            val updateSource = if (appConfig.isNull("updateSource")) "" else appConfig.getString("updateSource").trim()
            fallbackUrl = url.takeIf { it.isNotEmpty() }
            if (geckoviewRuntime == null) {
                val settings = GeckoRuntimeSettings.Builder().consoleOutput(true)
                // 配置仅作用于本 App，必须在 Gecko 启动前写入。
                val config = File(filesDir, "gecko-config.yaml")
                config.writeText(buildString {
                    // Release 构建默认忽略 Remote Settings 地址覆盖，需显式允许。
                    append("env:\n  MOZ_REMOTE_SETTINGS_DEVTOOLS: \"1\"\n")
                    append("prefs:\n")
                    append("  network.captive-portal-service.enabled: false\n")
                    append("  network.connectivity-service.enabled: false\n")
                    // 不访问远程配置端点，不关闭内容签名验证。
                    // 相应的安全规则和远程配置将不会在线更新。
                    append("  services.settings.server: \"\"\n")
                    if (updateSource.isNotEmpty() || File(filesDir, "web-container.xpi").isFile) {
                        append("  xpinstall.signatures.required: false\n")
                    }
                })
                settings.configFilePath(config.absolutePath)
                geckoviewRuntime = GeckoRuntime.create(applicationContext, settings.build())
            }
            val runtime = geckoviewRuntime!!
            session = GeckoSession()
            session.navigationDelegate = object : GeckoSession.NavigationDelegate {
                override fun onLocationChange(
                    session: GeckoSession, url: String?, perms: MutableList<GeckoSession.PermissionDelegate.ContentPermission>,
                    hasUserGesture: Boolean
                ) {
                    Log.d("ExtensionPage", "页面地址：$url")
                }

                override fun onLoadError(
                    session: GeckoSession, uri: String?, error: WebRequestError
                ): GeckoResult<String>? {
                    Log.e("ExtensionPage", "页面加载失败：$uri，$error")
                    // 替换插件时旧页面可能被引擎中断，不因此切回内置插件。
                    if (replacingSource) return null
                    showMessage("页面加载失败：$error")
                    if (!showingDefaultPage) {
                        installBuiltInAndOpen(runtime, fallbackUrl.takeUnless { urlAttempted })
                    }
                    return null
                }
            }
            session.open(runtime)
            findViewById<GeckoView>(R.id.geckoView).setSession(session)
            var updateStarted = false
            openCachedWeb(runtime) {
                // 先按 source 缓存 → url → 预置 dist 显示，再独立检查更新。
                if (updateSource.isNotEmpty() && !updateStarted && !isDestroyed && !isFinishing) {
                    updateStarted = true
                    update(this, updateSource, onAppReady = {
                        clearInstalledWeb(runtime)
                        openDownloadedApk()
                    }, onUnchanged = {
                        // 已展示正确页面，无更新时保持原样。
                    }, onFailure = { error ->
                        showMessage("更新失败：${error.message}")
                    }) { _ ->
                        showMessage("网页更新已准备好，下次启动生效")
                    }
                }
            }
        } catch (e: Exception) {
            showMessage("启动失败：${e.message}")
            if (::session.isInitialized && session.isOpen) {
                geckoviewRuntime?.let { installBuiltInAndOpen(it) }
            }
        }
    }

    private fun openDownloadedApk() {
        if (isDestroyed || isFinishing) return
        try {
            val uri = FileProvider.getUriForFile(
                this, "$packageName.fileprovider", File(filesDir, "update.apk")
            )
            startActivity(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            })
        } catch (e: Exception) {
            showMessage("无法打开安装包：${e.message}")
        }
    }

    private fun openCachedWeb(runtime: GeckoRuntime, onOpened: () -> Unit = {}) {
        val app = applicationContext
        Thread {
            try {
                val prefs = app.getSharedPreferences("update", MODE_PRIVATE)
                check(!prefs.getString("webVersion", "").isNullOrEmpty() ||
                    !prefs.getString("pendingSourceFingerprint", "").isNullOrEmpty()) { "尚未准备好 source" }
                val prepared = WebUpdate.cached(app)
                runOnUiThread {
                    if (!isDestroyed && !isFinishing) installAndOpen(runtime, prepared, onOpened)
                }
            } catch (_: Exception) {
                runOnUiThread { installBuiltInAndOpen(runtime, fallbackUrl, onOpened) }
            }
        }.start()
    }

    private fun clearInstalledWeb(runtime: GeckoRuntime) {
        // Gecko 的 profile 还会保留安装过的 XPI，连同该副本一起清理。
        val controller = runtime.webExtensionController
        controller.list().accept({ extensions ->
            extensions?.firstOrNull { it.id == "web-container@lisnote.com" }?.let { extension ->
                controller.uninstall(extension).accept(null, { error ->
                    showMessage("清理旧网页插件失败：${error?.message}")
                })
            }
        }, { error -> showMessage("读取旧网页插件失败：${error?.message}") })
    }

    private fun installBuiltInAndOpen(
        runtime: GeckoRuntime, url: String? = null, onOpened: () -> Unit = {}
    ) {
        if (isDestroyed || isFinishing) return
        if (url == null) {
            if (showingDefaultPage) return
            showingDefaultPage = true
        } else {
            urlAttempted = true
        }
        val controller = runtime.webExtensionController
        val id = "web-container@lisnote.com"
        var installed = false
        var ready = false
        var baseUrl = ""
        var opened = false
        fun openWhenReady() {
            if (installed && ready && !opened && !isDestroyed && !isFinishing) {
                opened = true
                session.loadUri(url ?: "${baseUrl}dist/index.html")
                onOpened()
            }
        }
        controller.setAddonManagerDelegate(object : WebExtensionController.AddonManagerDelegate {
            override fun onReady(extension: WebExtension) {
                if (extension.id == id && extension.isBuiltIn) {
                    baseUrl = extension.metaData.baseUrl.orEmpty()
                    ready = baseUrl.isNotEmpty()
                    openWhenReady()
                }
            }
        })
        fun install() {
            controller.ensureBuiltIn("resource://android/assets/web-container/", id).accept(
                { extension ->
                    if (extension == null) {
                        showMessage("内置插件安装失败")
                    } else {
                        installed = true
                        if (!extension.metaData.baseUrl.isNullOrEmpty()) {
                            baseUrl = extension.metaData.baseUrl
                            ready = true
                        }
                        openWhenReady()
                    }
                },
                { error -> showMessage("内置插件安装失败：${error?.message}") }
            )
        }
        controller.list().accept({ extensions ->
            val existing = extensions?.firstOrNull { it.id == id }
            if (existing != null && !existing.isBuiltIn) {
                controller.uninstall(existing).accept(
                    { install() },
                    { error -> showMessage("切换内置插件失败：${error?.message}") }
                )
            } else install()
        }, { error -> showMessage("读取插件失败：${error?.message}") })
    }

    private fun installAndOpen(
        runtime: GeckoRuntime, prepared: PreparedExtension, onOpened: () -> Unit = {}
    ) {
        val refreshPackage = prepared.changed || getSharedPreferences("update", MODE_PRIVATE)
            .getString("installedSourceFingerprint", "") != prepared.fingerprint
        if (refreshPackage) replacingSource = true
        val controller = runtime.webExtensionController
        var previous: WebExtension? = null
        var readyExtension: WebExtension? = null
        var awaitingReady = false
        var saveOnReady = true
        var opened = false
        var installationRunning = false
        var installationCompleted = false
        fun open(extension: WebExtension, saveVersions: Boolean = true) {
            if (isDestroyed || isFinishing || opened) return
            val ready = if (extension.metaData.baseUrl.isNullOrEmpty()) readyExtension ?: extension else extension
            val baseUrl = ready.metaData.baseUrl
            Log.d("ExtensionPage", "插件 ${extension.id} enabled=${extension.metaData.enabled} baseUrl=$baseUrl")
            if (baseUrl.isNullOrEmpty()) {
                awaitingReady = true
                saveOnReady = saveVersions
                return
            }
            awaitingReady = false
            opened = true
            fun loadPage() {
                if (isDestroyed || isFinishing) return
                showingDefaultPage = false
                urlAttempted = false
                replacingSource = false
                val prefs = getSharedPreferences("update", MODE_PRIVATE)
                val version = prepared.versions["webVersion"] ?: prefs.getString("webVersion", "").orEmpty()
                val page = Uri.parse("${baseUrl}dist/index.html").buildUpon()
                    .appendQueryParameter("webVersion", version).build().toString()
                session.load(GeckoSession.Loader().uri(page).flags(GeckoSession.LOAD_FLAGS_BYPASS_CACHE))
                if (saveVersions) {
                    prefs.edit {
                        for ((key, value) in prepared.versions) putString(key, value)
                        putString("installedSourceFingerprint", prepared.fingerprint)
                        if (prefs.getString("pendingSourceFingerprint", "") == prepared.fingerprint) {
                            remove("pendingSourceFingerprint")
                            remove("pendingWebVersion")
                            remove("pendingBundledExtFingerprint")
                        }
                        remove("extVersion")
                    }
                }
                if (prepared.changed && saveVersions) showMessage("插件更新完成")
                onOpened()
            }
            if (refreshPackage && saveVersions) {
                runtime.storageController.clearData(StorageController.ClearFlags.ALL_CACHES).accept(
                    { loadPage() },
                    { error ->
                        replacingSource = false
                        showMessage("刷新网页缓存失败：${error?.message}")
                    }
                )
            } else {
                loadPage()
            }
        }
        controller.setAddonManagerDelegate(object : WebExtensionController.AddonManagerDelegate {
            override fun onReady(extension: WebExtension) {
                if (extension.id != prepared.id) return
                readyExtension = extension
                if (installationRunning) {
                    if (installationCompleted) open(extension)
                } else if (awaitingReady) open(extension, saveOnReady)
            }
        })
        fun failed(error: Throwable?) {
            installationRunning = false
            replacingSource = false
            showMessage("插件安装失败：${error?.message ?: "未知错误"}")
            val cached = previous?.takeUnless { it.isBuiltIn }
            if (cached != null) open(cached, false)
            else installBuiltInAndOpen(runtime, fallbackUrl, onOpened)
        }
        fun install() {
            readyExtension = null
            installationRunning = true
            installationCompleted = false
            replacingSource = true
            controller.setPromptDelegate(object : WebExtensionController.PromptDelegate {
                override fun onInstallPromptRequest(
                    extension: WebExtension, permissions: Array<String>, origins: Array<String>
                ): GeckoResult<WebExtension.PermissionPromptResponse> = GeckoResult.fromValue(
                    WebExtension.PermissionPromptResponse(extension.id == prepared.id, false)
                )

                override fun onUpdatePrompt(
                    currentlyInstalled: WebExtension, updatedExtension: WebExtension,
                    newPermissions: Array<String>, newOrigins: Array<String>
                ): GeckoResult<AllowOrDeny> = GeckoResult.fromValue(
                    if (updatedExtension.id == prepared.id) AllowOrDeny.ALLOW else AllowOrDeny.DENY
                )
            })
            controller.install(Uri.fromFile(prepared.file).toString()).accept(
                { extension ->
                    if (extension == null) {
                        failed(null)
                    } else {
                        // 安装返回的元数据可能仍是旧实例，只用新一轮 onReady 的实例打开。
                        installationCompleted = true
                        readyExtension?.let { open(it) }
                    }
                },
                { error -> failed(error) }
            )
        }
        controller.list().accept({ extensions ->
            val existing = extensions?.firstOrNull { it.id == prepared.id }
            previous = existing
            when {
                existing != null && !existing.isBuiltIn && !refreshPackage -> open(existing)
                // 兼容旧版本曾安装的同 ID 内置扩展。
                existing?.isBuiltIn == true -> controller.uninstall(existing).accept(
                    { install() }, { error -> failed(error) }
                )
                else -> install()
            }
        }, { error -> failed(error) })
    }

    private fun showMessage(message: String) {
        if (!isDestroyed) Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    override fun onDestroy() {
        if (::session.isInitialized && session.isOpen) session.close()
        super.onDestroy()
    }

    companion object {
        private var geckoviewRuntime: GeckoRuntime? = null
    }
}
