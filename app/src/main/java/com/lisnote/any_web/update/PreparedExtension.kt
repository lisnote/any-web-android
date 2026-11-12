package com.lisnote.any_web.update

import java.io.File

internal data class PreparedExtension(
    val file: File, val id: String, val changed: Boolean, val versions: Map<String, String>,
    val fingerprint: String
)
