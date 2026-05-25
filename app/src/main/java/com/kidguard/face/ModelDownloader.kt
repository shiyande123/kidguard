package com.kidguard.face

import android.content.Context
import android.util.Log
import com.kidguard.util.DebugLog
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Downloads buffalo_s.onnx model from GitHub if not present.
 * Model URL: https://github.com/deepinsight/insightface/releases/download/v0.7/buffalo_s.zip
 * (inside the zip is buffalo_s.onnx)
 *
 * Model size: ~127MB zip, ~170MB extracted
 */
@Singleton
class ModelDownloader @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val _downloadProgress = MutableStateFlow<DownloadState>(DownloadState.Idle)
    val downloadProgress: StateFlow<DownloadState> = _downloadProgress

    sealed class DownloadState {
        data object Idle : DownloadState()
        data class Downloading(val bytesDownloaded: Long, val totalBytes: Long) : DownloadState()
        data class Success(val file: File) : DownloadState()
        data class Error(val message: String) : DownloadState()
    }

    private val modelDir: File
        get() = File(context.filesDir, "onnx_models").also { it.mkdirs() }

    val modelFile: File
        get() = File(modelDir, MODEL_FILENAME)

    val isModelAvailable: Boolean
        get() = modelFile.exists() && modelFile.length() > 10_000_000

    /**
     * Download buffalo_s.onnx from GitHub releases.
     * Returns immediately if model already exists.
     */
    suspend fun downloadModel() = withContext(Dispatchers.IO) {
        if (isModelAvailable) {
            DebugLog.log(TAG, "Model already available: ${modelFile.length()} bytes")
            _downloadProgress.value = DownloadState.Success(modelFile)
            return@withContext
        }

        _downloadProgress.value = DownloadState.Downloading(0, 0)

        try {
            val zipFile = File(modelDir, "buffalo_s.zip")

            // Download the zip file
            val url = URL(MODEL_URL)
            val conn = url.openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = 30_000
                conn.readTimeout = 60_000
                conn.instanceFollowRedirects = true
                conn.connect()

                val totalBytes = conn.contentLength.toLong().coerceAtLeast(1)
                DebugLog.log(TAG, "Downloading model from $MODEL_URL (total=$totalBytes)")

                conn.inputStream.use { input ->
                    FileOutputStream(zipFile).use { output ->
                        val buffer = ByteArray(8192)
                        var bytesDownloaded = 0L
                        var bytesRead: Int

                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                            bytesDownloaded += bytesRead
                            _downloadProgress.value = DownloadState.Downloading(bytesDownloaded, totalBytes)
                        }
                    }
                }
            } finally {
                conn.disconnect()
            }

            DebugLog.log(TAG, "Download complete, zip size=${zipFile.length()}")

            // Extract .onnx from zip
            _downloadProgress.value = DownloadState.Downloading(zipFile.length(), totalBytes * 2)

            try {
                extractOnnxFromZip(zipFile, modelFile)
                zipFile.delete()
                DebugLog.log(TAG, "Model extracted: ${modelFile.length()} bytes")
            } catch (e: Exception) {
                DebugLog.log(TAG, "Zip extraction failed: ${e.message}, trying direct download")
                // If extraction fails, maybe the zip contains multiple files
                // Let's try a different approach - direct onnx download
                downloadDirectOnnx()
                zipFile.delete()
            }

            if (modelFile.exists() && modelFile.length() > 10_000_000) {
                _downloadProgress.value = DownloadState.Success(modelFile)
                DebugLog.log(TAG, "Model ready: ${modelFile.length()} bytes")
            } else {
                throw Exception("Model file too small or missing after extraction")
            }

        } catch (e: Exception) {
            val msg = "Download failed: ${e.javaClass.simpleName}: ${e.message}"
            DebugLog.log(TAG, msg)
            _downloadProgress.value = DownloadState.Error(msg)
        }
    }

    private fun extractOnnxFromZip(zipFile: File, destFile: File) {
        java.util.zip.ZipFile(zipFile).use { zip ->
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (!entry.isDirectory && entry.name.endsWith(".onnx")) {
                    DebugLog.log(TAG, "Found ONNX in zip: ${entry.name}")
                    zip.getInputStream(entry).use { input ->
                        FileOutputStream(destFile).use { output ->
                            input.copyTo(output)
                        }
                    }
                    return
                }
            }
            throw Exception("No .onnx file found in zip")
        }
    }

    private suspend fun downloadDirectOnnx() = withContext(Dispatchers.IO) {
        // Try to download .onnx directly from HF mirror or another source
        // buffalo_s.onnx might be available separately
        val onnxUrl = "https://github.com/deepinsight/insightface/releases/download/v0.7/buffalo_s.onnx"
        try {
            val url = URL(onnxUrl)
            val conn = url.openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = 60_000
                conn.readTimeout = 120_000
                conn.instanceFollowRedirects = true
                conn.connect()

                if (conn.responseCode == 200) {
                    val totalBytes = conn.contentLength.toLong().coerceAtLeast(1)
                    _downloadProgress.value = DownloadState.Downloading(0, totalBytes)

                    conn.inputStream.use { input ->
                        FileOutputStream(modelFile).use { output ->
                            val buffer = ByteArray(8192)
                            var downloaded = 0L
                            var read: Int
                            while (input.read(buffer).also { read = it } != -1) {
                                output.write(buffer, 0, read)
                                downloaded += read
                                _downloadProgress.value = DownloadState.Downloading(downloaded, totalBytes)
                            }
                        }
                    }
                    DebugLog.log(TAG, "Direct ONNX download complete: ${modelFile.length()} bytes")
                } else {
                    throw Exception("Direct ONNX download returned ${conn.responseCode}")
                }
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            DebugLog.log(TAG, "Direct ONNX download failed: ${e.message}")
            throw e
        }
    }

    fun reset() {
        _downloadProgress.value = DownloadState.Idle
    }

    companion object {
        private const val TAG = "ModelDownloader"
        private const val MODEL_FILENAME = "buffalo_s.onnx"
        // buffalo_s.zip contains buffalo_s.onnx
        private const val MODEL_URL = "https://github.com/deepinsight/insightface/releases/download/v0.7/buffalo_s.zip"
    }
}
