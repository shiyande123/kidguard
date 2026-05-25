package com.kidguard.lock

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.kidguard.R
import com.kidguard.util.DebugLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.coroutines.resume

/**
 * 语音警告：红警音效 → "禁止XXX看手机"
 *
 * 所有等待逻辑均使用 suspendCancellableCoroutine，协程取消时自动释放资源，
 * 不会因 TTS/MediaPlayer 回调丢失而永久阻塞线程。
 */
class VoiceWarning(private val context: Context) {

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var mediaPlayer: MediaPlayer? = null

    private var initSuccess: Boolean? = null   // null=未完成, true/false=已完成

    init {
        try {
            tts = TextToSpeech(context.applicationContext) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    var result = tts?.setLanguage(Locale.CHINESE)
                    if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                        result = tts?.setLanguage(Locale.SIMPLIFIED_CHINESE)
                    }
                    if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                        tts?.setLanguage(Locale.getDefault())
                    }
                    ttsReady = true
                    DebugLog.log(TAG, "TTS ready=$ttsReady")
                } else {
                    DebugLog.log(TAG, "TTS init failed: $status")
                    ttsReady = false
                }
                synchronized(this) {
                    initSuccess = ttsReady
                    (this as Object).notifyAll()
                }
            }
        } catch (e: Exception) {
            DebugLog.log(TAG, "TTS error: ${e.message}")
            ttsReady = false
            synchronized(this) {
                initSuccess = false
                (this as Object).notifyAll()
            }
        }
    }

    /**
     * 等待 TTS 初始化完成（最多 3 秒）。
     * 使用 Object.wait/notify 替代 CountDownLatch，避免协程中持有监控器的问题。
     */
    private suspend fun awaitInit() {
        synchronized(this) {
            if (initSuccess != null) return
            (this as Object).wait(3_000L)
        }
    }

    /**
     * 播放完整警告：红警音效 → 语音
     * 改为 suspend 函数，内部使用 suspendCancellableCoroutine。
     */
    suspend fun speakAndWait(childName: String): Boolean {
        awaitInit()

        // 1. 播放红警音效
        DebugLog.log(TAG, "Playing red alert...")
        playRedAlert()

        // 2. 播放语音
        if (ttsReady && tts != null) {
            val spoken = trySpeak(childName)
            if (spoken) return true
        }

        DebugLog.log(TAG, "TTS not available, alert only")
        return false
    }

    private suspend fun playRedAlert() {
        try {
            val afd = context.resources.openRawResourceFd(R.raw.red_alert)
            val mp = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                afd.close()
                prepare()
            }
            mediaPlayer = mp

            withTimeoutOrNull(6_000L) {
                suspendCancellableCoroutine { cont ->
                    mp.setOnCompletionListener {
                        DebugLog.log(TAG, "Red alert done")
                        if (cont.isActive) cont.resume(Unit)
                    }
                    mp.setOnErrorListener { _, _, _ ->
                        DebugLog.log(TAG, "Red alert error (MediaPlayer)")
                        if (cont.isActive) cont.resume(Unit)
                        true
                    }
                    cont.invokeOnCancellation {
                        DebugLog.log(TAG, "Red alert cancelled")
                        try { mp.stop() } catch (_: Exception) {}
                    }
                    mp.start()
                }
            } ?: run {
                DebugLog.log(TAG, "Red alert timed out")
            }
        } catch (e: CancellationException) {
            DebugLog.log(TAG, "Red alert cancelled: ${e.message}")
            throw e
        } catch (e: Exception) {
            DebugLog.log(TAG, "Red alert error: ${e.message}")
        } finally {
            try { mediaPlayer?.release(); mediaPlayer = null } catch (_: Exception) {}
        }
    }

    private suspend fun trySpeak(childName: String): Boolean {
        val text = "禁止${childName}看手机"
        DebugLog.log(TAG, "TTS: $text")

        val currentTts = tts ?: return false

        withTimeoutOrNull(10_000L) {
            suspendCancellableCoroutine { cont ->
                currentTts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) {}
                    override fun onDone(id: String?) {
                        DebugLog.log(TAG, "TTS done")
                        if (cont.isActive) cont.resume(Unit)
                    }
                    @Deprecated("Deprecated in Java")
                    override fun onError(id: String?) {
                        DebugLog.log(TAG, "TTS error (utterance)")
                        if (cont.isActive) cont.resume(Unit)
                    }
                })

                val params = android.os.Bundle()
                val id = "w_${System.currentTimeMillis()}"
                currentTts.speak(text, TextToSpeech.QUEUE_FLUSH, params, id)

                cont.invokeOnCancellation {
                    DebugLog.log(TAG, "TTS cancelled, stopping")
                    try { currentTts.stop() } catch (_: Exception) {}
                }
            }
        } ?: run {
            DebugLog.log(TAG, "TTS timed out")
        }

        return true
    }

    fun stop() {
        try { tts?.stop(); tts?.shutdown() } catch (_: Exception) {}
        try { mediaPlayer?.release(); mediaPlayer = null } catch (_: Exception) {}
    }

    companion object {
        private const val TAG = "VoiceWarning"
    }
}
