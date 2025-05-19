package com.lisnote.any.any_web

import android.annotation.SuppressLint
import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.tencent.smtt.export.external.interfaces.WebResourceResponse
import com.tencent.smtt.sdk.WebView
import com.tencent.smtt.sdk.WebViewClient
import java.net.URL

class MainActivity : AppCompatActivity() {
    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        WebView.setWebContentsDebuggingEnabled(true)
        val webView = findViewById<WebView>(R.id.webview)
        webView.settings.apply {
            javaScriptEnabled = true
            allowFileAccess = true
            setAllowFileAccessFromFileURLs(true)
            setAllowUniversalAccessFromFileURLs(true)
            domStorageEnabled = true
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView?,
                url: String?
            ): WebResourceResponse? {
                if (url != null && url.startsWith("http://local.any-web.com/")) {
                    val path = URL(url).path
                    return assetResponse(path.removePrefix("/"))
                }
                return super.shouldInterceptRequest(view, url)
            }
        }

        webView.loadUrl("http://local.any-web.com")

        enableEdgeToEdge()
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
    }

    private fun assetResponse(path: String): WebResourceResponse? {
        val cleanPath = path.ifBlank { "index.html" }

        val finalPath = if (assetExists(cleanPath)) {
            cleanPath
        } else if (assetExists("index.html")) {
            "index.html"
        } else {
            return null
        }

        val inputStream = assets.open(finalPath)
        val mimeType = when {
            finalPath.endsWith(".html") -> "text/html"
            finalPath.endsWith(".js") -> "application/javascript"
            finalPath.endsWith(".css") -> "text/css"
            else -> "text/plain"
        }
        return WebResourceResponse(mimeType, "utf-8", inputStream)
    }

    private fun assetExists(path: String): Boolean {
        return try {
            val dir = path.substringBeforeLast('/', "")
            val name = path.substringAfterLast('/')
            assets.list(dir)?.contains(name) == true
        } catch (e: Exception) {
            false
        }
    }
}
