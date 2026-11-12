package com.lisnote.any_web.update

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast

internal fun Context.showUpdateNotice(message: String) {
    val app = applicationContext
    Handler(Looper.getMainLooper()).post {
        Toast.makeText(app, message, Toast.LENGTH_SHORT).show()
    }
}
