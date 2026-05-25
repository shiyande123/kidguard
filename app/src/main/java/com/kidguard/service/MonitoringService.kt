package com.kidguard.service

import androidx.lifecycle.LifecycleService
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat

import com.kidguard.face.FaceAnalyzer
import com.kidguard.face.FaceDetectorManager
import com.kidguard.face.FaceEmbeddingModel
import com.kidguard.face.FaceRegistry
import com.kidguard.lock.LockController
import com.kidguard.data.db.SettingsDao
import com.kidguard.util.DebugLog
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import javax.inject.Inject

@AndroidEntryPoint
class MonitoringService : LifecycleService() {

    @Inject lateinit var faceAnalyzer: FaceAnalyzer
    @Inject lateinit var lockController: LockController
    @Inject lateinit var faceRegistry: FaceRegistry
    @Inject lateinit var settingsDao: SettingsDao
    @Inject lateinit var faceDetectorManager: FaceDetectorManager
    @Inject lateinit var embeddingModel: FaceEmbeddingModel

    private var cameraProvider: ProcessCameraProvider? = null
    private lateinit var cameraExecutor: ExecutorService
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    @Volatile private var currentLockDelay = 3
    @Volatile private var isDestroyed = false
    @Volatile private var stoppedByUser = false
    private var frameCount = 0

    fun stopByUser() {
        stoppedByUser = true
        stopSelf()
    }

    override fun onBind(intent: Intent): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isDestroyed = false
        DebugLog.init(this)
        DebugLog.log(TAG, "=== Service onCreate ===")

        cameraExecutor = Executors.newSingleThreadExecutor()

        // 初始化检测器
        DebugLog.log(TAG, "Initializing detector...")
        faceDetectorManager.init()

        // 初始化 TFLite 人脸识别模型
        DebugLog.log(TAG, "Initializing TFLite embedding model...")
        embeddingModel.init()

        try {
            MonitoringNotification.createChannel(this)
            startForeground(
                MonitoringNotification.getNotificationId(),
                MonitoringNotification.createNotification(this)
            )
            DebugLog.log(TAG, "Foreground started")
        } catch (e: Exception) {
            DebugLog.log(TAG, "Foreground FAILED: ${e.message}")
        }

        // 加载设置和人脸
        serviceScope.launch {
            loadSettingsAndFaces()
        }

        // 5秒后再次加载人脸（给数据库初始化时间）
        serviceScope.launch {
            delay(5_000L)
            if (!isDestroyed) {
                DebugLog.log(TAG, "Delayed face reload (5s)...")
                try { loadSettingsAndFaces() } catch (e: Exception) { DebugLog.log(TAG, "Delayed reload error: ${e.message}") }
            }
        }

        // 10秒后检查检测器和模型是否就绪，必要时重试初始化
        serviceScope.launch {
            delay(10_000L)
            if (!isDestroyed) {
                val detReady = faceDetectorManager.isInitialized
                val embReady = embeddingModel.isLoaded
                if (!detReady || !embReady) {
                    DebugLog.log(TAG, "Retrying init: detector=$detReady, embedding=$embReady")
                    if (!detReady) faceDetectorManager.init()
                    if (!embReady) embeddingModel.init()
                    if (!detReady || !embReady) {
                        DebugLog.log(TAG, "Init retry result: detector=${faceDetectorManager.isInitialized}, embedding=${embeddingModel.isLoaded}")
                    }
                }
            }
        }

        // 每30秒重新加载
        serviceScope.launch {
            while (isActive && !isDestroyed) {
                delay(30_000L)
                if (isDestroyed) break
                try { loadSettingsAndFaces() } catch (e: Exception) { DebugLog.log(TAG, "Reload error: ${e.message}") }
            }
        }

        // 监听人脸录入广播，立即重新加载
        try {
            val filter = android.content.IntentFilter(ACTION_FACE_ENROLLED)
            registerReceiver(faceEnrollReceiver, filter)
            DebugLog.log(TAG, "Face enroll listener registered")
        } catch (e: Exception) {
            DebugLog.log(TAG, "Failed to register face listener: ${e.message}")
        }

        setupFaceDetectionListener()
        startCamera()

        // 注入 Service 的 serviceScope，使 LockController 协程随 Service 生命周期取消
        lockController.setScope(serviceScope)

        // 注册屏幕解锁监听
        lockController.registerUnlockListener()

        // 初始化语音警告
        lockController.initVoiceWarning()
    }

    private suspend fun loadSettingsAndFaces() {
        val settings = settingsDao.get().firstOrNull()
        if (settings != null) {
            faceRegistry.setSensitivity(settings.sensitivity)
            currentLockDelay = settings.lockDelay
        }
        faceRegistry.loadFaces()

        // Register all children with SeetaFace2 FaceRecognizer2 in-memory DB
        val refFaces = faceRegistry.getAllReferenceFaces()
        if (refFaces.isNotEmpty()) {
            embeddingModel.registerAllChildren(refFaces)
        }

        val count = faceRegistry.getRegisteredChildIds().size
        DebugLog.log(TAG, "Faces loaded: $count, lockDelay=$currentLockDelay, admin=${lockController.isAdminActive()}")
    }

    private fun setupFaceDetectionListener() {
        faceAnalyzer.setOnFaceDetectedListener { childId, childName ->
            if (isDestroyed) return@setOnFaceDetectedListener
            DebugLog.log(TAG, "Face detected! childId=$childId, name=$childName")
            if (childId != null) {
                DebugLog.log(TAG, ">>> MATCH! Locking in ${currentLockDelay}s...")
                try {
                    lockController.handleFaceDetected(childId, childName ?: "unknown", currentLockDelay)
                } catch (e: Exception) {
                    DebugLog.log(TAG, "Lock FAILED: ${e.message}")
                }
            }
        }
    }

    private fun startCamera() {
        if (isDestroyed) return
        try {
            val future = ProcessCameraProvider.getInstance(this)
            future.addListener({
                try {
                    if (!isDestroyed) {
                        cameraProvider = future.get()
                        bindCameraUseCases()
                        DebugLog.log(TAG, "Camera provider ready")
                    }
                } catch (e: Exception) {
                    DebugLog.log(TAG, "Camera provider FAILED: ${e.message}")
                }
            }, ContextCompat.getMainExecutor(this))
        } catch (e: Exception) { DebugLog.log(TAG, "startCamera FAILED: ${e.message}") }
    }

    private fun bindCameraUseCases() {
        if (isDestroyed) return
        val cp = cameraProvider ?: return

        try {
            val imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
                if (isDestroyed) {
                    try { imageProxy.close() } catch (_: Exception) {}
                    return@setAnalyzer
                }
                frameCount++
                if (frameCount % 10 == 0) {
                    DebugLog.log(TAG, "Frame #$frameCount analyzed, registered=${faceRegistry.getRegisteredChildIds().size}")
                }
                try {
                    faceAnalyzer.analyze(imageProxy)
                } catch (e: Exception) {
                    DebugLog.log(TAG, "Analyzer CRASHED: ${e.message}")
                    try { imageProxy.close() } catch (_: Exception) {}
                }
            }

            cp.unbindAll()
            cp.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, imageAnalysis)
            DebugLog.log(TAG, "Camera bound OK")
        } catch (e: Exception) { DebugLog.log(TAG, "bind FAILED: ${e.message}") }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        isDestroyed = true
        DebugLog.log(TAG, "=== Service onDestroy ===")
        try { unregisterReceiver(faceEnrollReceiver) } catch (_: Exception) {}
        try { lockController.unregisterUnlockListener() } catch (_: Exception) {}
        try { lockController.destroy() } catch (_: Exception) {}
        if (stoppedByUser) {
            try { getSharedPreferences("kidguard_prefs", MODE_PRIVATE).edit().putBoolean("monitoring_enabled", false).apply() } catch (_: Exception) {}
        }
        try { cameraProvider?.unbindAll() } catch (_: Exception) {}
        cameraProvider = null
        try { cameraExecutor.shutdownNow() } catch (_: Exception) {}
        serviceScope.cancel()
        try { faceDetectorManager.close() } catch (_: Exception) {}
        try { embeddingModel.close() } catch (_: Exception) {}
        try { lockController.unlock() } catch (_: Exception) {}
        super.onDestroy()
    }

    // 收到人脸录入广播后立即重新加载
    private val faceEnrollReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(ctx: android.content.Context?, intent: android.content.Intent?) {
            if (intent?.action == ACTION_FACE_ENROLLED) {
                DebugLog.log(TAG, ">>> Face enrolled broadcast received, reloading...")
                serviceScope.launch {
                    try { loadSettingsAndFaces() } catch (e: Exception) { DebugLog.log(TAG, "Reload error: ${e.message}") }
                }
            }
        }
    }

    companion object {
        private const val TAG = "KidGuard-Monitor"
        const val ACTION_FACE_ENROLLED = "com.kidguard.FACE_ENROLLED"
        const val EXTRA_USER_STOPPED = "user_stopped"
    }
}
