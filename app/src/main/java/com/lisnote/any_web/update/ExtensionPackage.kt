package com.lisnote.any_web.update

import org.json.JSONObject
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import java.util.zip.ZipFile
import java.util.zip.CRC32
import java.util.zip.CheckedInputStream
import java.security.MessageDigest

internal object ExtensionPackage {
    fun prepare(
        directory: File, changed: Boolean, versions: Map<String, String>,
        copyExtension: ((File) -> Unit)? = null
    ): PreparedExtension {
        val destination = File(directory, "web-container.xpi")
        if (!changed && destination.isFile) {
            val manifest = ZipFile(destination).use { zip ->
                check(zip.getEntry("dist/index.html") != null) { "XPI 中缺少 dist/index.html" }
                val entry = zip.getEntry("manifest.json") ?: error("XPI 中缺少 manifest.json")
                zip.getInputStream(entry).bufferedReader(Charsets.UTF_8)
                    .use { JSONObject(it.readText()) }
            }
            return PreparedExtension(destination, extensionId(manifest), false, versions, fingerprint(destination))
        }
        if (changed) prepareHtml(directory, checkNotNull(copyExtension))
        val html = File(directory, "html")
        val manifest = JSONObject(File(html, "manifest.json").readText())
        val id = extensionId(manifest)
        check(File(html, "dist/index.html").isFile) { "扩展中缺少 dist/index.html" }
        if (changed || !destination.isFile) {
            val temp = File.createTempFile("extension-", ".xpi", directory)
            try {
                ZipOutputStream(temp.outputStream().buffered()).use { zip ->
                    for (file in html.walkTopDown().filter { it.isFile }) {
                        val name = file.relativeTo(html).invariantSeparatorsPath
                        // 合并后的自定义扩展不再使用原包的签名元数据。
                        if (name.startsWith("META-INF/", ignoreCase = true)) continue
                        zip.putNextEntry(ZipEntry(name))
                        file.inputStream().use { it.copyTo(zip) }
                        zip.closeEntry()
                    }
                }
                check(temp.renameTo(destination)) { "无法保存 XPI" }
            } finally {
                temp.delete()
            }
        }
        return PreparedExtension(destination, id, changed, versions, fingerprint(destination))
    }

    // 只读取 ZIP 中央目录，不重复解压大文件；内容变化会改变条目的 CRC 和大小。
    private fun fingerprint(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        ZipFile(file).use { zip ->
            for (entry in zip.entries().asSequence().sortedBy { it.name }) {
                digest.update("${entry.name}\u0000${entry.crc}\u0000${entry.size}\n".toByteArray(Charsets.UTF_8))
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun prepareHtml(directory: File, copyExtension: (File) -> Unit) {
        val archive = File(directory, "web.zip")
        check(archive.isFile) { "web.zip 缺失" }
        val staging = File.createTempFile("html-", ".pending", directory)
        check(staging.delete() && staging.mkdir()) { "无法创建解压目录" }
        val html = File(directory, "html")
        val backup = File(directory, "html.previous")
        try {
            // 先复制预置插件，再完整替换其中的网页目录。
            copyExtension(staging)
            val dist = File(staging, "dist")
            check(!dist.exists() || dist.deleteRecursively()) { "无法删除预置 dist" }
            check(dist.mkdirs()) { "无法创建 dist" }
            extract(archive, dist)
            check(File(dist, "index.html").isFile) { "web.zip 根目录缺少 index.html" }
            // 恢复上一次被中断的目录切换。
            if (backup.exists()) {
                if (!html.exists()) {
                    check(backup.renameTo(html)) { "无法恢复原 html 目录" }
                } else {
                    check(backup.deleteRecursively()) { "无法清理旧备份" }
                }
            }
            val hadPrevious = html.exists()
            if (hadPrevious) check(html.renameTo(backup)) { "无法备份原 html 目录" }
            if (!staging.renameTo(html)) {
                if (hadPrevious) check(backup.renameTo(html)) { "恢复失败，旧文件保留在 html.previous" }
                error("无法安装 html 目录")
            }
            backup.deleteRecursively()
        } finally {
            staging.deleteRecursively()
        }
    }

    private fun extract(archive: File, target: File) {
        val root = target.canonicalPath + File.separator
        ZipFile(archive).use { zip ->
            val entries = zip.entries()
            check(entries.hasMoreElements()) { "${archive.name} 是空压缩包" }
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                val output = File(target, entry.name).canonicalFile
                check(output.path.startsWith(root)) { "压缩包包含非法路径：${entry.name}" }
                if (entry.isDirectory) {
                    check(output.isDirectory || output.mkdirs()) { "无法创建目录：${entry.name}" }
                } else {
                    val parent = output.parentFile!!
                    check(parent.isDirectory || parent.mkdirs()) { "无法创建父目录" }
                    CheckedInputStream(zip.getInputStream(entry), CRC32()).use { input ->
                        output.outputStream().use { stream ->
                            val received = input.copyTo(stream)
                            check(entry.size < 0 || received == entry.size) { "解压内容不完整" }
                            check(entry.crc < 0 || input.checksum.value == entry.crc) { "解压校验失败" }
                        }
                    }
                }
            }
        }
    }

    private fun extensionId(manifest: JSONObject): String {
        val id = manifest.optJSONObject("browser_specific_settings")?.optJSONObject("gecko")
            ?.optString("id").orEmpty().ifEmpty {
                manifest.optJSONObject("applications")?.optJSONObject("gecko")?.optString("id")
                    .orEmpty()
            }
        check(id.isNotBlank()) { "manifest.json 必须指定 gecko.id" }
        return id
    }
}
