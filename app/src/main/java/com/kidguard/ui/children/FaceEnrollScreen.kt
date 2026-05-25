package com.kidguard.ui.children

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.kidguard.face.FaceDetectorManager
import com.kidguard.face.FaceEmbeddingModel
import com.kidguard.face.LandmarkPoint
import com.kidguard.util.DebugLog
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

// Hilt entry point
@dagger.hilt.EntryPoint
@dagger.hilt.InstallIn(SingletonComponent::class)
interface FaceDetectorEntryPoint {
    fun faceDetectorManager(): FaceDetectorManager
    fun faceEmbeddingModel(): FaceEmbeddingModel
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FaceEnrollScreen(
    childId: Long,
    navController: NavController,
    viewModel: ChildrenViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val detectorManager = remember {
        EntryPointAccessors.fromApplication(
            context.applicationContext,
            FaceDetectorEntryPoint::class.java
        ).faceDetectorManager()
    }
    val embeddingModel = remember {
        EntryPointAccessors.fromApplication(
            context.applicationContext,
            FaceDetectorEntryPoint::class.java
        ).faceEmbeddingModel()
    }

    var hasCameraPermission by remember { mutableStateOf(false) }
    var isCaptured by remember { mutableStateOf(false) }
    var isProcessing by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf("") }

    val imageCapture = remember { ImageCapture.Builder().build() }
    var cameraProvider by remember { mutableStateOf<ProcessCameraProvider?>(null) }

    LaunchedEffect(Unit) {
        try {
            detectorManager.init()
            embeddingModel.init()
        } catch (e: Throwable) {
            Log.e("FaceEnroll", "Init failed", e)
            statusMessage = "初始化失败: ${e.javaClass.simpleName}"
            Toast.makeText(context, "人脸检测初始化失败: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            try {
                cameraProvider?.unbindAll()
            } catch (_: Exception) {}
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted -> hasCameraPermission = granted }

    val coroutineScope = rememberCoroutineScope()

    suspend fun processImageUri(uri: Uri) {
        isCaptured = true
        isProcessing = true
        statusMessage = "正在识别人脸..."
        Log.d("FaceEnroll", ">>> processImageUri: uri=$uri, childId=$childId")

        try {
            // --- IO-heavy work on Dispatchers.IO ---
            val result = withContext(Dispatchers.IO) {
                val inputStream = context.contentResolver.openInputStream(uri)
                if (inputStream == null) {
                    Log.e("FaceEnroll", "FAILED: inputStream is null")
                    return@withContext "无法读取图片"
                }
                val bitmap = BitmapFactory.decodeStream(inputStream)
                inputStream.close()

                if (bitmap == null) {
                    Log.e("FaceEnroll", "BitmapFactory returned null")
                    return@withContext "图片解码失败"
                }
                Log.d("FaceEnroll", "Bitmap: ${bitmap.width}x${bitmap.height}")

                if (!detectorManager.isInitialized) {
                    try { bitmap.recycle() } catch (_: Exception) {}
                    return@withContext "SeetaFace2 检测器未初始化"
                }
                if (!embeddingModel.isLoaded) {
                    try { bitmap.recycle() } catch (_: Exception) {}
                    return@withContext "SeetaFace2 识别模型未加载，请检查模型文件"
                }

                // SeetaFace2 detection + landmark detection
                val faces = detectorManager.detectSync(bitmap)
                Log.d("FaceEnroll", "SeetaFace2 detection: ${faces.size} face(s)")
                if (faces.isEmpty()) {
                    try { bitmap.recycle() } catch (_: Exception) {}
                    return@withContext "未检测到人脸，请重新选择"
                }

                // Take largest face
                val bestFace = faces.maxByOrNull { it.rect.width * it.rect.height }!!
                val landmarks = bestFace.landmarks

                // Enroll: align/crop face, save reference, register
                val landmarkPoints = landmarks.map { LandmarkPoint(it.x, it.y) }.toTypedArray()

                // Align and crop face to 192×192
                val aligned = embeddingModel.alignAndCropForEnrollment(bitmap, landmarks[0], landmarks[1])
                try { bitmap.recycle() } catch (_: Exception) {}

                if (aligned == null) {
                    return@withContext "人脸对齐失败"
                }

                // Save reference bitmap (192×192 aligned face)
                val refPath = embeddingModel.saveReferenceBitmapSync(childId, aligned)

                if (refPath == null) {
                    aligned.recycle()
                    return@withContext "人脸图片保存失败"
                }

                // Save landmarks to file
                val lmPath = embeddingModel.saveLandmarksSync(childId, landmarkPoints)
                if (lmPath == null) {
                    aligned.recycle()
                    return@withContext "特征点保存失败"
                }

                // Register with ONNX: use aligned 192×192 face directly (already aligned)
                val index = embeddingModel.enrollFromAlignedSync(childId, aligned)
                aligned.recycle()

                if (index < 0) {
                    return@withContext "人脸注册失败"
                }

                // Save reference paths to Room DB (also IO)
                viewModel.saveFaceReference(childId, refPath, lmPath)
                Log.d("FaceEnroll", "Enrolled childId=$childId, ref=$refPath")
                null // null means success
            }

            // --- Back on Main thread: update UI state ---
            if (result != null) {
                // 录入失败，清理已保存的临时文件
                try { embeddingModel.deleteReferenceData(childId) } catch (_: Exception) {}
                statusMessage = result
                isProcessing = false
                isCaptured = false
            } else {
                statusMessage = "人脸录入成功！"
                isProcessing = false
            }
        } catch (e: Exception) {
            // 录入异常，清理已保存的临时文件
            try { embeddingModel.deleteReferenceData(childId) } catch (_: Exception) {}
            statusMessage = "处理失败: ${e.message}"
            isProcessing = false
            isCaptured = false
            Log.e("FaceEnroll", "Error: ${e.message}", e)
        }
    }

    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? -> if (uri != null) coroutineScope.launch { processImageUri(uri) } }

    LaunchedEffect(Unit) {
        val p = Manifest.permission.CAMERA
        hasCameraPermission = ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED
        if (!hasCameraPermission) permissionLauncher.launch(p)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("人脸录入", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier.fillMaxSize().padding(paddingValues).padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (!hasCameraPermission) {
                Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.Face, null, Modifier.size(64.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(16.dp))
                        Text("选择人脸图片", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(8.dp))
                        Text("从相册选择包含人脸的照片", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                OutlinedButton(onClick = { galleryLauncher.launch("image/*") }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.PhotoLibrary, null)
                    Spacer(Modifier.height(4.dp))
                    Text("从相册选择")
                }
            } else if (!isCaptured) {
                Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                    AndroidView(
                        factory = { ctx ->
                            val previewView = PreviewView(ctx)
                            val future = ProcessCameraProvider.getInstance(ctx)
                            future.addListener({
                                val cp = future.get()
                                cameraProvider = cp
                                val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
                                try {
                                    cp.unbindAll()
                                    cp.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_FRONT_CAMERA, preview, imageCapture)
                                } catch (e: Exception) {
                                    Log.e("FaceEnroll", "Camera bind failed", e)
                                }
                            }, ContextCompat.getMainExecutor(ctx))
                            previewView
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }

                Text("请将人脸放在画面中央", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = { galleryLauncher.launch("image/*") }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.PhotoLibrary, null)
                        Spacer(Modifier.height(4.dp))
                        Text("相册")
                    }
                    Button(
                        onClick = {
                            val photoFile = File(context.filesDir, "face_${childId}_${System.currentTimeMillis()}.jpg")
                            val opts = ImageCapture.OutputFileOptions.Builder(photoFile).build()
                            imageCapture.takePicture(opts, ContextCompat.getMainExecutor(context),
                                object : ImageCapture.OnImageSavedCallback {
                                    override fun onImageSaved(o: ImageCapture.OutputFileResults) {
                                        coroutineScope.launch { processImageUri(Uri.fromFile(photoFile)) }
                                    }
                                    override fun onError(e: ImageCaptureException) {
                                        statusMessage = "拍照失败: ${e.message}"
                                        isCaptured = false
                                        Log.e("FaceEnroll", "Capture failed", e)
                                    }
                                }
                            )
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.CameraAlt, null)
                        Spacer(Modifier.height(4.dp))
                        Text("拍照")
                    }
                }
            } else {
                if (isProcessing) {
                    Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(Modifier.size(64.dp))
                            Spacer(Modifier.height(16.dp))
                            Text(statusMessage, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                } else {
                    Text(statusMessage, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                        color = if (statusMessage.contains("成功")) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                    Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.Face, null, Modifier.size(120.dp),
                            tint = if (statusMessage.contains("成功")) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                    }
                    if (statusMessage.contains("成功")) {
                        Button(onClick = { navController.popBackStack() }, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Default.Check, null); Spacer(Modifier.height(4.dp)); Text("完成")
                        }
                    } else {
                        OutlinedButton(onClick = { isCaptured = false; statusMessage = "" }, modifier = Modifier.fillMaxWidth()) {
                            Text("重新选择")
                        }
                    }
                }
            }
        }
    }
}
