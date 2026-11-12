package com.lisnote.any_web.update

import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import android.util.AtomicFile

// 写入调用方提供的临时文件，所有分段校验通过后才返回。
internal object Downloader {
    private const val THREADS = 5
    private const val CHUNK_SIZE = 5 * 1024 * 1024L

    fun download(source: String, destination: File) {
        val temp = File.createTempFile("${destination.name}-", ".part", destination.parentFile)
        try {
            downloadToTemp(source, temp)
            val file = AtomicFile(destination)
            val output = file.startWrite()
            try {
                temp.inputStream().use { it.copyTo(output) }
                file.finishWrite(output)
            } catch (e: Exception) {
                file.failWrite(output)
                throw e
            }
        } finally {
            temp.delete()
        }
    }

    private fun downloadToTemp(source: String, target: File) {
        val probe = connection(source)
        probe.setRequestProperty("Range", "bytes=0-0")
        val size: Long
        val validator: String?
        try {
            if (probe.responseCode == HttpURLConnection.HTTP_OK) {
                copyResponse(probe, target)
                return
            }
            check(probe.responseCode == HttpURLConnection.HTTP_PARTIAL) {
                "下载失败：HTTP ${probe.responseCode}"
            }
            val range = Regex("bytes 0-0/(\\d+)").matchEntire(
                probe.getHeaderField("Content-Range").orEmpty()
            ) ?: error("服务器返回了无效的 Content-Range")
            size = range.groupValues[1].toLong()
            check(size > 0) { "文件大小无效" }
            probe.inputStream.use {
                check(it.read() != -1 && it.read() == -1) { "分段探测响应不完整" }
            }
            validator = probe.getHeaderField("ETag")
                ?.takeUnless { it.startsWith("W/") }
                ?: probe.getHeaderField("Last-Modified")
        } finally {
            probe.disconnect()
        }

        // 没有版本校验标识时避免拼接下载过程中发生变化的文件。
        if (size <= CHUNK_SIZE || validator == null) {
            downloadSingle(source, target)
            return
        }

        RandomAccessFile(target, "rw").use { it.setLength(size) }
        val pool = Executors.newFixedThreadPool(THREADS)
        val nextStart = AtomicLong(0)
        try {
            val tasks = (0 until THREADS).map {
                pool.submit(Callable {
                    while (true) {
                        // 原子领取下一段，避免多个线程下载同一个区间。
                        val start = nextStart.getAndAdd(CHUNK_SIZE)
                        if (start >= size) break
                        val end = minOf(start + CHUNK_SIZE, size) - 1
                        downloadRange(source, target, start, end, size, validator)
                    }
                })
            }
            // 等每个线程都停止写入后，才允许清理临时文件或回退下载。
            val results = tasks.map { task -> runCatching { task.get() } }
            if (results.any { it.isFailure }) {
                downloadSingle(source, target)
            }
        } finally {
            pool.shutdown()
        }
    }

    private fun downloadRange(
        source: String, target: File, start: Long, end: Long, size: Long, validator: String
    ) {
        val connection = connection(source)
        connection.setRequestProperty("Range", "bytes=$start-$end")
        connection.setRequestProperty("If-Range", validator)
        try {
            check(connection.responseCode == HttpURLConnection.HTTP_PARTIAL) {
                "服务器不再支持分段，或文件已变化"
            }
            check(connection.getHeaderField("Content-Range") == "bytes $start-$end/$size") {
                "分段范围不匹配"
            }
            connection.inputStream.use { input ->
                RandomAccessFile(target, "rw").use { output ->
                    output.seek(start)
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var remaining = end - start + 1
                    while (remaining > 0) {
                        val count = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                        check(count > 0) { "分段下载不完整" }
                        output.write(buffer, 0, count)
                        remaining -= count
                    }
                    check(input.read() == -1) { "分段数据超出范围" }
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun downloadSingle(source: String, target: File) {
        val connection = connection(source)
        try {
            check(connection.responseCode == HttpURLConnection.HTTP_OK) {
                "下载失败：HTTP ${connection.responseCode}"
            }
            copyResponse(connection, target)
        } finally {
            connection.disconnect()
        }
    }

    private fun copyResponse(connection: HttpURLConnection, target: File) {
        val received = connection.inputStream.use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        }
        val expected = connection.contentLengthLong
        check(expected < 0 || received == expected) { "下载内容不完整" }
    }

    private fun connection(source: String): HttpURLConnection =
        (URL(source).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("Accept-Encoding", "identity")
        }
}
