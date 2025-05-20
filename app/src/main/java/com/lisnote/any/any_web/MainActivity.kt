package com.lisnote.any.any_web

import android.os.Bundle
import android.util.Log
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import org.mozilla.gecko.util.ThreadUtils
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView

class MainActivity : AppCompatActivity() {
    private lateinit var session: GeckoSession

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val view = findViewById<GeckoView>(R.id.geckoView)
        session = GeckoSession()
        val config = GeckoRuntimeSettings.Builder().consoleOutput(true).build()
        val runtime = GeckoRuntime.create(this, config)
        runtime
            .webExtensionController
            .ensureBuiltIn(
                "resource://android/assets/addons/cors-unblock/",
                "messaging@example.com"
            )
        session.open(runtime)
        view.setSession(session)
        session.loadUri("https://mes.opezw.com")

        enableEdgeToEdge()
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
    }
}
