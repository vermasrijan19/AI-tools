package com.vermasrijan.pixelnpu.agent

import com.vermasrijan.pixelnpu.agent.litert.ModelDownload
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.coroutineContext

/**
 * Downloads a model file into a directory, resuming a previous partial download
 * (`<name>.part`) with an HTTP Range request.
 */
object ModelDownloader {
    private const val PROGRESS_INTERVAL_MS = 250L

    /**
     * Downloads [download] into [dir] and returns the finished file.
     *
     * @param hfToken Hugging Face access token, sent as a bearer token; the Gemma 4 repo is gated.
     * @param onProgress Called with (bytes downloaded, total bytes), at most every 250 ms.
     * @param url Source URL; defaults to the model's Hugging Face URL.
     * @throws IOException on network or storage errors, including a missing or rejected token.
     */
    suspend fun download(
        download: ModelDownload,
        dir: File,
        hfToken: String?,
        onProgress: (Long, Long) -> Unit,
        url: String = download.url,
    ): File = withContext(Dispatchers.IO) {
        dir.mkdirs()
        val target = File(dir, download.fileName)
        if (target.length() == download.sizeBytes) return@withContext target

        val part = File(dir, "${download.fileName}.part")
        if (part.length() > download.sizeBytes) part.delete()
        if (part.length() < download.sizeBytes) fetch(url, part, download.sizeBytes, hfToken, onProgress)

        if (part.length() != download.sizeBytes) {
            throw IOException(
                "Download incomplete: got ${part.length()} of ${download.sizeBytes} bytes. Try again to resume."
            )
        }
        if (!part.renameTo(target)) throw IOException("Could not move ${part.name} to ${target.name}.")
        target
    }

    /** Appends the rest of [url] to [part], or rewrites it if the server doesn't honor the range. */
    private suspend fun fetch(
        url: String,
        part: File,
        totalBytes: Long,
        hfToken: String?,
        onProgress: (Long, Long) -> Unit,
    ) {
        val dir = part.absoluteFile.parentFile
        val remaining = totalBytes - part.length()
        if (dir.usableSpace < remaining) {
            throw IOException("Not enough free storage: need %.1f GB more.".format(remaining / 1e9))
        }

        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 30_000
            readTimeout = 60_000
            if (!hfToken.isNullOrBlank()) setRequestProperty("Authorization", "Bearer ${hfToken.trim()}")
            if (part.length() > 0) setRequestProperty("Range", "bytes=${part.length()}-")
        }
        try {
            val append = when (val code = connection.responseCode) {
                HttpURLConnection.HTTP_PARTIAL -> true
                HttpURLConnection.HTTP_OK -> false // Server ignored the range; start over.
                HttpURLConnection.HTTP_UNAUTHORIZED, HttpURLConnection.HTTP_FORBIDDEN -> throw IOException(
                    "Hugging Face refused the download (HTTP $code). Accept the Gemma license at " +
                        "huggingface.co/${ModelDownload.REPO} and enter a read token from huggingface.co/settings/tokens."
                )
                else -> throw IOException("Download failed: HTTP $code ${connection.responseMessage}")
            }

            var downloaded = if (append) part.length() else 0L
            var lastReport = 0L
            connection.inputStream.use { input ->
                FileOutputStream(part, append).use { output ->
                    val buffer = ByteArray(1 shl 20)
                    while (true) {
                        coroutineContext.ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        downloaded += read
                        val now = System.currentTimeMillis()
                        if (now - lastReport >= PROGRESS_INTERVAL_MS) {
                            lastReport = now
                            onProgress(downloaded, totalBytes)
                        }
                    }
                }
            }
            onProgress(downloaded, totalBytes)
        } finally {
            connection.disconnect()
        }
    }
}
