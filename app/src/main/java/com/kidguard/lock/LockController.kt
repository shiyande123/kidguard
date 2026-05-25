package com.kidguard.lock

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import com.kidguard.data.db.LockLogDao
import com.kidguard.data.model.LockLog
import com.kidguard.util.DebugLog
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.currentCoroutineJob
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LockController @Inject constructor(
    private val deviceAdminManager: DeviceAdminManager,
    private val lockLogDao: LockLogDao,
    @ApplicationContext private val context: Context
) {
    /** 由 Service 注入其 serviceScope，协程随 Service 取消而取消 */
    private var serviceScope: CoroutineScope? = null

    fun setScope(scope: CoroutineScope) {
        serviceScope = scope
    }

    private val lastLockTime = AtomicLong(0)
    private val isProcessing = AtomicBoolean(false)  // 防止重复触发
    private var voiceWarning: VoiceWarning? = null
    /** Track the current lock-coroutine job so we can cancel it on destroy */
    private var currentLockJob: Job? = null

    private val unlockReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_USER_PRESENT) {
                lastLockTime.set(0)
                isProcessing.set(false)
                DebugLog.log(TAG, "Screen unlocked, reset")
            }
        }
    }

    fun isAdminActive() = deviceAdminManager.isAdminActive()

    fun initVoiceWarning() {
        voiceWarning = VoiceWarning(context)
        DebugLog.log(TAG, "VoiceWarning initialized")
    }

    fun registerUnlockListener() {
        try {
            val filter = IntentFilter(Intent.ACTION_USER_PRESENT)
            context.registerReceiver(unlockReceiver, filter)
        } catch (_: Exception) {}
    }

    fun unregisterUnlockListener() {
        try { context.unregisterReceiver(unlockReceiver) } catch (_: Exception) {}
    }

    /**
     * 检测到人脸：播放一次声音 → 等3秒 → 锁屏
     * 解锁前不会重复触发
     */
    fun handleFaceDetected(childId: Long, childName: String, lockDelay: Int = 3) {
        // 已在处理中或刚锁过，跳过
        if (isProcessing.get()) {
            DebugLog.log(TAG, "Already processing, skip")
            return
        }
        val now = System.currentTimeMillis()
        if (now - lastLockTime.get() < LOCK_COOLDOWN_MS) {
            DebugLog.log(TAG, "Cooldown active, skip")
            return
        }

        val scope = serviceScope
        if (scope == null) {
            DebugLog.log(TAG, "serviceScope not set, skip")
            return
        }

        // 标记为处理中，解锁前不会重复触发
        isProcessing.set(true)

        scope.launch {
            // Track job so it can be cancelled when service is destroyed
            currentLockJob = currentCoroutineJob()

            DebugLog.log(TAG, "Face matched: $childName")

            // 播放语音（speakAndWait 已是 suspend 函数，无需切 IO）
            try {
                voiceWarning?.speakAndWait(childName)
            } catch (e: Exception) {
                DebugLog.log(TAG, "Voice error: ${e.message}")
            }

            // 等 3 秒后锁屏
            DebugLog.log(TAG, "Waiting ${lockDelay}s...")
            delay(lockDelay * 1000L)

            if (deviceAdminManager.isAdminActive()) {
                deviceAdminManager.lockNow()
                lastLockTime.set(System.currentTimeMillis())
                DebugLog.log(TAG, ">>> LOCKED for $childName")

                lockLogDao.insert(
                    LockLog(
                        childId = childId,
                        childName = childName,
                        lockDuration = lockDelay,
                        reason = "face_detected"
                    )
                )
            } else {
                DebugLog.log(TAG, "Admin not active!")
            }
            // Reset processing flag when coroutine completes (success or cancelled)
            isProcessing.set(false)
            currentLockJob = null
        }.invokeOnCompletion { cause ->
            // If cancelled (service destroyed), reset flag so detection can resume
            if (cause is CancellationException) {
                DebugLog.log(TAG, "Lock coroutine cancelled (service destroyed)")
                isProcessing.set(false)
                currentLockJob = null
            }
        }
    }

    fun unlock() {
        lastLockTime.set(0)
        isProcessing.set(false)
    }

    fun destroy() {
        voiceWarning?.stop()
        voiceWarning = null
        serviceScope = null
        // serviceScope 由 Service 生命周期管理，Service.onDestroy 时会 cancel
    }

    companion object {
        private const val TAG = "KidGuard-Lock"
        private const val LOCK_COOLDOWN_MS = 10_000L
    }
}
