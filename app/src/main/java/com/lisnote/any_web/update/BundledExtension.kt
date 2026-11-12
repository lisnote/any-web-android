package com.lisnote.any_web.update

import android.content.Context
import java.io.File
import java.security.MessageDigest

internal object BundledExtension {
    private const val ROOT = "web-container"

    private fun files(context: Context, path: String = ROOT): List<String> =
        context.assets.list(path).orEmpty().sorted().flatMap { name ->
            val child = "$path/$name"
            if (context.assets.list(child).isNullOrEmpty()) listOf(child) else files(context, child)
        }

    fun fingerprint(context: Context): String {
        val digest = MessageDigest.getInstance("SHA-256")
        for (path in files(context)) {
            digest.update(path.toByteArray(Charsets.UTF_8))
            digest.update(0.toByte())
            context.assets.open(path).use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun copyTo(context: Context, directory: File) {
        for (path in files(context)) {
            val output = File(directory, path.removePrefix("$ROOT/"))
            check(output.parentFile!!.isDirectory || output.parentFile!!.mkdirs()) { "无法创建扩展目录" }
            context.assets.open(path).use { input ->
                output.outputStream().use { input.copyTo(it) }
            }
        }
    }
}
