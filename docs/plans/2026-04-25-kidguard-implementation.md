# KidGuard 儿童守护 - 实施计划

> **For Hermes:** Use subagent-driven-development skill to implement this plan task-by-task.

**Goal:** 开发一款 Android 应用，通过人脸识别检测儿童使用手机，自动锁屏防止沉迷，家长可远程管理。

**Architecture:** 前端使用 Jetpack Compose + Material 3，后端逻辑使用 Kotlin Coroutines。人脸检测使用 Google ML Kit，锁屏使用 Android Device Admin API。数据持久化使用 Room 数据库。

**Tech Stack:** Kotlin, Jetpack Compose, Google ML Kit (Face Detection), Room DB, Android Device Admin API, Hilt (DI), CameraX

---

## 项目结构

```
app/src/main/java/com/kidguard/
├── KidGuardApplication.kt          # Application 类
├── di/                              # 依赖注入
│   └── AppModule.kt
├── data/                            # 数据层
│   ├── db/
│   │   ├── KidGuardDatabase.kt
│   │   ├── ChildDao.kt
│   │   ├── LockLogDao.kt
│   │   └── SettingsDao.kt
│   ├── model/
│   │   ├── Child.kt
│   │   ├── LockLog.kt
│   │   └── Settings.kt
│   └── repository/
│       ├── ChildRepository.kt
│       └── SettingsRepository.kt
├── face/                            # 人脸检测模块
│   ├── FaceDetectorManager.kt
│   ├── FaceAnalyzer.kt
│   └── FaceRegistry.kt
├── lock/                            # 锁屏模块
│   ├── DeviceAdminManager.kt
│   ├── LockController.kt
│   └── KidGuardDeviceAdmin.kt
├── service/                         # 后台服务
│   ├── MonitoringService.kt
│   └── MonitoringNotification.kt
├── ui/                              # UI 层
│   ├── theme/
│   │   ├── Color.kt
│   │   ├── Theme.kt
│   │   └── Type.kt
│   ├── navigation/
│   │   └── NavGraph.kt
│   ├── home/
│   │   ├── HomeScreen.kt
│   │   └── HomeViewModel.kt
│   ├── children/
│   │   ├── ChildrenScreen.kt
│   │   ├── ChildrenViewModel.kt
│   │   ├── AddChildScreen.kt
│   │   └── FaceEnrollScreen.kt
│   ├── settings/
│   │   ├── SettingsScreen.kt
│   │   └── SettingsViewModel.kt
│   ├── logs/
│   │   ├── LogsScreen.kt
│   │   └── LogsViewModel.kt
│   └── lock/
│       └── LockScreen.kt
└── util/
    ├── TimeUtils.kt
    └── Extensions.kt
```

---

## Task 1: 项目初始化

**Objective:** 创建 Android 项目基础结构，配置依赖

**Files:**
- Create: `build.gradle.kts` (project)
- Create: `app/build.gradle.kts`
- Create: `settings.gradle.kts`
- Create: `gradle.properties`
- Create: `app/src/main/AndroidManifest.xml`
- Create: `app/src/main/java/com/kidguard/KidGuardApplication.kt`

**Step 1: 创建项目配置**

```kotlin
// settings.gradle.kts
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "KidGuard"
include(":app")
```

**Step 2: 配置 app build.gradle.kts**

```kotlin
// app/build.gradle.kts
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.dagger.hilt.android")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.kidguard"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.kidguard"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.8"
    }
}

dependencies {
    // Core
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.activity:activity-compose:1.9.3")

    // Compose
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.8.5")

    // CameraX
    implementation("androidx.camera:camera-core:1.4.1")
    implementation("androidx.camera:camera-camera2:1.4.1")
    implementation("androidx.camera:camera-lifecycle:1.4.1")
    implementation("androidx.camera:camera-view:1.4.1")

    // ML Kit Face Detection
    implementation("com.google.mlkit:face-detection:16.2.0")

    // Room
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // Hilt
    implementation("com.google.dagger:hilt-android:2.53.1")
    ksp("com.google.dagger:hilt-android-compiler:2.53.1")
    implementation("androidx.hilt:hilt-navigation-compose:1.2.0")

    // DataStore
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // Testing
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
```

**Step 3: 创建 AndroidManifest.xml**

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <!-- 权限 -->
    <uses-permission android:name="android.permission.CAMERA" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_CAMERA" />
    <uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />
    <uses-permission android:name="android.permission.WAKE_LOCK" />
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />

    <uses-feature android:name="android.hardware.camera" android:required="true" />

    <application
        android:name=".KidGuardApplication"
        android:allowBackup="true"
        android:icon="@mipmap/ic_launcher"
        android:label="@string/app_name"
        android:supportsRtl="true"
        android:theme="@style/Theme.KidGuard">

        <activity
            android:name=".ui.MainActivity"
            android:exported="true"
            android:theme="@style/Theme.KidGuard">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>

        <!-- 设备管理器 -->
        <receiver
            android:name=".lock.KidGuardDeviceAdmin"
            android:exported="true"
            android:permission="android.permission.BIND_DEVICE_ADMIN">
            <meta-data
                android:name="android.app.device_admin"
                android:resource="@xml/device_admin_policies" />
            <intent-filter>
                <action android:name="android.app.action.DEVICE_ADMIN_ENABLED" />
            </intent-filter>
        </receiver>

        <!-- 监控服务 -->
        <service
            android:name=".service.MonitoringService"
            android:foregroundServiceType="camera"
            android:exported="false" />

        <!-- 开机启动 -->
        <receiver
            android:name=".service.BootReceiver"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.BOOT_COMPLETED" />
            </intent-filter>
        </receiver>
    </application>
</manifest>
```

**Step 4: 创建 Application 类**

```kotlin
// KidGuardApplication.kt
package com.kidguard

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class KidGuardApplication : Application()
```

**Step 5: 创建 device_admin_policies.xml**

```xml
<?xml version="1.0" encoding="utf-8"?>
<device-admin xmlns:android="http://schemas.android.com/apk/res/android">
    <uses-policies>
        <force-lock />
    </uses-policies>
</device-admin>
```

**Verification:** 项目能编译通过，`./gradlew assembleDebug`

---

## Task 2: 数据模型与数据库

**Objective:** 定义数据模型，创建 Room 数据库

**Files:**
- Create: `app/src/main/java/com/kidguard/data/model/Child.kt`
- Create: `app/src/main/java/com/kidguard/data/model/LockLog.kt`
- Create: `app/src/main/java/com/kidguard/data/model/Settings.kt`
- Create: `app/src/main/java/com/kidguard/data/db/ChildDao.kt`
- Create: `app/src/main/java/com/kidguard/data/db/LockLogDao.kt`
- Create: `app/src/main/java/com/kidguard/data/db/SettingsDao.kt`
- Create: `app/src/main/java/com/kidguard/data/db/KidGuardDatabase.kt`

**Step 1: Child 模型**

```kotlin
// data/model/Child.kt
package com.kidguard.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "children")
data class Child(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val age: Int,
    val faceEmbedding: FloatArray? = null, // 人脸特征向量
    val createdAt: Long = System.currentTimeMillis(),
    val isActive: Boolean = true
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Child) return false
        return id == other.id
    }

    override fun hashCode(): Int = id.hashCode()
}
```

**Step 2: LockLog 模型**

```kotlin
// data/model/LockLog.kt
package com.kidguard.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "lock_logs")
data class LockLog(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val childId: Long,
    val childName: String,
    val timestamp: Long = System.currentTimeMillis(),
    val lockDuration: Int = 0, // 锁定时长（秒）
    val reason: String = "face_detected" // 锁定原因
)
```

**Step 3: Settings 模型**

```kotlin
// data/model/Settings.kt
package com.kidguard.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "settings")
data class Settings(
    @PrimaryKey
    val id: Int = 1,
    val isEnabled: Boolean = true,
    val dailyTimeLimit: Int = 120, // 每日使用时长限制（分钟）
    val lockDelay: Int = 3, // 检测到人脸后延迟锁定（秒）
    val sensitivity: Float = 0.7f, // 人脸检测灵敏度
    val workingHoursStart: Int = 8, // 工作时间开始（24小时制）
    val workingHoursEnd: Int = 22, // 工作时间结束
    val notifyParent: Boolean = true, // 锁定时通知家长
    val soundEnabled: Boolean = true // 锁定时播放提示音
)
```

**Step 4: DAO 接口**

```kotlin
// data/db/ChildDao.kt
package com.kidguard.data.db

import androidx.room.*
import com.kidguard.data.model.Child
import kotlinx.coroutines.flow.Flow

@Dao
interface ChildDao {
    @Query("SELECT * FROM children WHERE isActive = 1")
    fun getAllActive(): Flow<List<Child>>

    @Query("SELECT * FROM children WHERE id = :id")
    suspend fun getById(id: Long): Child?

    @Insert
    suspend fun insert(child: Child): Long

    @Update
    suspend fun update(child: Child)

    @Delete
    suspend fun delete(child: Child)

    @Query("UPDATE children SET faceEmbedding = :embedding WHERE id = :id")
    suspend fun updateFaceEmbedding(id: Long, embedding: FloatArray)
}
```

```kotlin
// data/db/LockLogDao.kt
package com.kidguard.data.db

import androidx.room.*
import com.kidguard.data.model.LockLog
import kotlinx.coroutines.flow.Flow

@Dao
interface LockLogDao {
    @Query("SELECT * FROM lock_logs ORDER BY timestamp DESC LIMIT :limit")
    fun getRecent(limit: Int = 100): Flow<List<LockLog>>

    @Query("SELECT * FROM lock_logs WHERE childId = :childId AND timestamp > :since ORDER BY timestamp DESC")
    fun getByChildSince(childId: Long, since: Long): Flow<List<LockLog>>

    @Query("SELECT SUM(lockDuration) FROM lock_logs WHERE timestamp > :since")
    suspend fun getTotalLockTime(since: Long): Int?

    @Insert
    suspend fun insert(log: LockLog)

    @Query("DELETE FROM lock_logs WHERE timestamp < :before")
    suspend fun deleteOlderThan(before: Long)
}
```

```kotlin
// data/db/SettingsDao.kt
package com.kidguard.data.db

import androidx.room.*
import com.kidguard.data.model.Settings
import kotlinx.coroutines.flow.Flow

@Dao
interface SettingsDao {
    @Query("SELECT * FROM settings WHERE id = 1")
    fun get(): Flow<Settings?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(settings: Settings)
}
```

**Step 5: 数据库**

```kotlin
// data/db/KidGuardDatabase.kt
package com.kidguard.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import com.kidguard.data.model.Child
import com.kidguard.data.model.LockLog
import com.kidguard.data.model.Settings

@Database(
    entities = [Child::class, LockLog::class, Settings::class],
    version = 1,
    exportSchema = false
)
abstract class KidGuardDatabase : RoomDatabase() {
    abstract fun childDao(): ChildDao
    abstract fun lockLogDao(): LockLogDao
    abstract fun settingsDao(): SettingsDao
}
```

**Verification:** 编译通过，数据库表能创建

---

## Task 3: 依赖注入模块

**Objective:** 配置 Hilt 依赖注入

**Files:**
- Create: `app/src/main/java/com/kidguard/di/AppModule.kt`

```kotlin
// di/AppModule.kt
package com.kidguard.di

import android.content.Context
import androidx.room.Room
import com.kidguard.data.db.ChildDao
import com.kidguard.data.db.KidGuardDatabase
import com.kidguard.data.db.LockLogDao
import com.kidguard.data.db.SettingsDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): KidGuardDatabase {
        return Room.databaseBuilder(
            context,
            KidGuardDatabase::class.java,
            "kidguard.db"
        ).build()
    }

    @Provides
    fun provideChildDao(db: KidGuardDatabase): ChildDao = db.childDao()

    @Provides
    fun provideLockLogDao(db: KidGuardDatabase): LockLogDao = db.lockLogDao()

    @Provides
    fun provideSettingsDao(db: KidGuardDatabase): SettingsDao = db.settingsDao()
}
```

**Verification:** 编译通过

---

## Task 4: 人脸检测模块

**Objective:** 实现人脸检测、注册、比对功能

**Files:**
- Create: `app/src/main/java/com/kidguard/face/FaceDetectorManager.kt`
- Create: `app/src/main/java/com/kidguard/face/FaceAnalyzer.kt`
- Create: `app/src/main/java/com/kidguard/face/FaceRegistry.kt`

**Step 1: FaceDetectorManager**

```kotlin
// face/FaceDetectorManager.kt
package com.kidguard.face

import android.content.Context
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FaceDetectorManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val detector: FaceDetector by lazy {
        val options = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
            .setMinFaceSize(0.15f)
            .build()
        FaceDetection.getClient(options)
    }

    fun detect(image: InputImage, onResult: (List<Face>) -> Unit, onError: (Exception) -> Unit) {
        detector.process(image)
            .addOnSuccessListener { faces -> onResult(faces) }
            .addOnFailureListener { e -> onError(e) }
    }

    fun close() {
        detector.close()
    }
}
```

**Step 2: FaceAnalyzer (CameraX 分析器)**

```kotlin
// face/FaceAnalyzer.kt
package com.kidguard.face

import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FaceAnalyzer @Inject constructor(
    private val detectorManager: FaceDetectorManager,
    private val registry: FaceRegistry
) : ImageAnalysis.Analyzer {

    private var onFaceDetected: ((Face, Boolean) -> Unit)? = null
    private var isProcessing = false

    fun setOnFaceDetectedListener(listener: (Face, Boolean) -> Unit) {
        onFaceDetected = listener
    }

    override fun analyze(imageProxy: ImageProxy) {
        if (isProcessing) {
            imageProxy.close()
            return
        }

        isProcessing = true
        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            imageProxy.close()
            isProcessing = false
            return
        }

        val image = InputImage.fromMediaImage(
            mediaImage,
            imageProxy.imageInfo.rotationDegrees
        )

        detectorManager.detect(
            image = image,
            onResult = { faces ->
                isProcessing = false
                imageProxy.close()

                if (faces.isNotEmpty()) {
                    val face = faces.first()
                    val isKnownChild = registry.isKnownChild(face)
                    onFaceDetected?.invoke(face, isKnownChild)
                }
            },
            onError = { e ->
                isProcessing = false
                imageProxy.close()
                Log.e("FaceAnalyzer", "Detection failed", e)
            }
        )
    }
}
```

**Step 3: FaceRegistry**

```kotlin
// face/FaceRegistry.kt
package com.kidguard.face

import com.google.mlkit.vision.face.Face
import com.kidguard.data.db.ChildDao
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FaceRegistry @Inject constructor(
    private val childDao: ChildDao
) {
    private val registeredFaces = mutableMapOf<Long, FloatArray>()

    suspend fun loadFaces() {
        val children = childDao.getAllActive().value // 需要改为 suspend 方式
        children.forEach { child ->
            child.faceEmbedding?.let { embedding ->
                registeredFaces[child.id] = embedding
            }
        }
    }

    fun isKnownChild(face: Face): Boolean {
        // 简化版：检查是否有已注册的儿童
        // 实际应用中应该比较人脸特征向量
        return registeredFaces.isNotEmpty()
    }

    fun registerFace(childId: Long, embedding: FloatArray) {
        registeredFaces[childId] = embedding
    }

    fun unregisterFace(childId: Long) {
        registeredFaces.remove(childId)
    }
}
```

**Verification:** 编译通过，人脸检测功能可测试

---

## Task 5: 锁屏模块

**Objective:** 实现设备管理员锁屏功能

**Files:**
- Create: `app/src/main/java/com/kidguard/lock/KidGuardDeviceAdmin.kt`
- Create: `app/src/main/java/com/kidguard/lock/DeviceAdminManager.kt`
- Create: `app/src/main/java/com/kidguard/lock/LockController.kt`

**Step 1: DeviceAdminReceiver**

```kotlin
// lock/KidGuardDeviceAdmin.kt
package com.kidguard.lock

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent

class KidGuardDeviceAdmin : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        // 设备管理器已启用
    }

    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
        // 设备管理器已禁用
    }
}
```

**Step 2: DeviceAdminManager**

```kotlin
// lock/DeviceAdminManager.kt
package com.kidguard.lock

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DeviceAdminManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    private val adminComponent = ComponentName(context, KidGuardDeviceAdmin::class.java)

    fun isAdminActive(): Boolean {
        return dpm.isAdminActive(adminComponent)
    }

    fun lockNow() {
        if (isAdminActive()) {
            dpm.lockNow()
        }
    }
}
```

**Step 3: LockController**

```kotlin
// lock/LockController.kt
package com.kidguard.lock

import android.util.Log
import com.kidguard.data.db.LockLogDao
import com.kidguard.data.model.LockLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LockController @Inject constructor(
    private val deviceAdminManager: DeviceAdminManager,
    private val lockLogDao: LockLogDao
) {
    private val scope = CoroutineScope(Dispatchers.Main)
    private var isLocked = false

    fun handleFaceDetected(childId: Long, childName: String, delaySeconds: Int = 3) {
        if (isLocked) return

        scope.launch {
            Log.d("LockController", "Child detected: $childName, locking in ${delaySeconds}s")
            delay(delaySeconds * 1000L)

            if (!isLocked) {
                deviceAdminManager.lockNow()
                isLocked = true

                // 记录锁定日志
                lockLogDao.insert(
                    LockLog(
                        childId = childId,
                        childName = childName,
                        reason = "face_detected"
                    )
                )

                Log.d("LockController", "Screen locked for $childName")
            }
        }
    }

    fun unlock() {
        isLocked = false
    }
}
```

**Verification:** 编译通过，设备管理器能正常启用和锁屏

---

## Task 6: 后台监控服务

**Objective:** 创建前台服务持续监控人脸

**Files:**
- Create: `app/src/main/java/com/kidguard/service/MonitoringService.kt`
- Create: `app/src/main/java/com/kidguard/service/MonitoringNotification.kt`
- Create: `app/src/main/java/com/kidguard/service/BootReceiver.kt`

**Step 1: MonitoringNotification**

```kotlin
// service/MonitoringNotification.kt
package com.kidguard.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import com.kidguard.R

object MonitoringNotification {
    private const val CHANNEL_ID = "kidguard_monitoring"
    private const val NOTIFICATION_ID = 1001

    fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "儿童守护监控",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "KidGuard 正在监控中"
            }
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    fun createNotification(context: Context): Notification {
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle("KidGuard 守护中")
            .setContentText("正在监控儿童使用情况")
            .setSmallIcon(R.drawable.ic_shield)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}
```

**Step 2: MonitoringService**

```kotlin
// service/MonitoringService.kt
package com.kidguard.service

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.kidguard.face.FaceAnalyzer
import com.kidguard.lock.LockController
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MonitoringService : Service() {

    @Inject lateinit var faceAnalyzer: FaceAnalyzer
    @Inject lateinit var lockController: LockController

    private var cameraProvider: ProcessCameraProvider? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        MonitoringNotification.createChannel(this)
        startForeground(1001, MonitoringNotification.createNotification(this))
        startCamera()
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()
            bindCameraUseCases()
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindCameraUseCases() {
        val cameraProvider = cameraProvider ?: return

        val preview = Preview.Builder().build()

        val imageAnalysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()

        imageAnalysis.setAnalyzer(ContextCompat.getMainExecutor(this)) { imageProxy ->
            faceAnalyzer.analyze(imageProxy)
        }

        val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA

        try {
            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(
                this,
                cameraSelector,
                preview,
                imageAnalysis
            )
        } catch (e: Exception) {
            Log.e("MonitoringService", "Camera binding failed", e)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraProvider?.unbindAll()
    }
}
```

**Step 3: BootReceiver**

```kotlin
// service/BootReceiver.kt
package com.kidguard.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val serviceIntent = Intent(context, MonitoringService::class.java)
            context.startForegroundService(serviceIntent)
        }
    }
}
```

**Verification:** 服务能在后台运行，开机自启动

---

## Task 7: UI 主题与导航

**Objective:** 创建 Material 3 主题和导航结构

**Files:**
- Create: `app/src/main/java/com/kidguard/ui/theme/Color.kt`
- Create: `app/src/main/java/com/kidguard/ui/theme/Theme.kt`
- Create: `app/src/main/java/com/kidguard/ui/theme/Type.kt`
- Create: `app/src/main/java/com/kidguard/ui/navigation/NavGraph.kt`
- Create: `app/src/main/java/com/kidguard/ui/MainActivity.kt`

**Step 1: 主题文件**

```kotlin
// ui/theme/Color.kt
package com.kidguard.ui.theme

import androidx.compose.ui.graphics.Color

val Purple80 = Color(0xFFD0BCFF)
val PurpleGrey80 = Color(0xFFCCC2DC)
val Pink80 = Color(0xFFEFB8C8)

val Purple40 = Color(0xFF6650a4)
val PurpleGrey40 = Color(0xFF625b71)
val Pink40 = Color(0xFF7D5260)

// KidGuard 专用颜色
val ShieldGreen = Color(0xFF4CAF50)
val AlertOrange = Color(0xFFFF9800)
val DangerRed = Color(0xFFF44336)
val SafeBlue = Color(0xFF2196F3)
```

```kotlin
// ui/theme/Theme.kt
package com.kidguard.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme = darkColorScheme(
    primary = Purple80,
    secondary = PurpleGrey80,
    tertiary = Pink80
)

private val LightColorScheme = lightColorScheme(
    primary = Purple40,
    secondary = PurpleGrey40,
    tertiary = Pink40
)

@Composable
fun KidGuardTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
```

**Step 2: 导航图**

```kotlin
// ui/navigation/NavGraph.kt
package com.kidguard.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.kidguard.ui.children.AddChildScreen
import com.kidguard.ui.children.ChildrenScreen
import com.kidguard.ui.children.FaceEnrollScreen
import com.kidguard.ui.home.HomeScreen
import com.kidguard.ui.logs.LogsScreen
import com.kidguard.ui.settings.SettingsScreen

sealed class Screen(val route: String) {
    object Home : Screen("home")
    object Children : Screen("children")
    object AddChild : Screen("add_child")
    object FaceEnroll : Screen("face_enroll/{childId}")
    object Settings : Screen("settings")
    object Logs : Screen("logs")
}

@Composable
fun NavGraph(navController: NavHostController) {
    NavHost(navController = navController, startDestination = Screen.Home.route) {
        composable(Screen.Home.route) {
            HomeScreen(navController = navController)
        }
        composable(Screen.Children.route) {
            ChildrenScreen(navController = navController)
        }
        composable(Screen.AddChild.route) {
            AddChildScreen(navController = navController)
        }
        composable(Screen.FaceEnroll.route) { backStackEntry ->
            val childId = backStackEntry.arguments?.getString("childId")?.toLongOrNull() ?: 0L
            FaceEnrollScreen(childId = childId, navController = navController)
        }
        composable(Screen.Settings.route) {
            SettingsScreen(navController = navController)
        }
        composable(Screen.Logs.route) {
            LogsScreen(navController = navController)
        }
    }
}
```

**Step 3: MainActivity**

```kotlin
// ui/MainActivity.kt
package com.kidguard.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.navigation.compose.rememberNavController
import com.kidguard.ui.navigation.NavGraph
import com.kidguard.ui.theme.KidGuardTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            KidGuardTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val navController = rememberNavController()
                    NavGraph(navController = navController)
                }
            }
        }
    }
}
```

**Verification:** 应用能启动，显示主页

---

## Task 8: 首页 UI

**Objective:** 创建家长控制面板首页

**Files:**
- Create: `app/src/main/java/com/kidguard/ui/home/HomeScreen.kt`
- Create: `app/src/main/java/com/kidguard/ui/home/HomeViewModel.kt`

**Step 1: HomeViewModel**

```kotlin
// ui/home/HomeViewModel.kt
package com.kidguard.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kidguard.data.db.ChildDao
import com.kidguard.data.db.LockLogDao
import com.kidguard.data.db.SettingsDao
import com.kidguard.data.model.Child
import com.kidguard.data.model.Settings
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val childDao: ChildDao,
    private val lockLogDao: LockLogDao,
    private val settingsDao: SettingsDao
) : ViewModel() {

    val children: StateFlow<List<Child>> = childDao.getAllActive()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val settings: StateFlow<Settings?> = settingsDao.get()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private val _isMonitoring = MutableStateFlow(false)
    val isMonitoring: StateFlow<Boolean> = _isMonitoring

    fun toggleMonitoring() {
        _isMonitoring.value = !_isMonitoring.value
    }
}
```

**Step 2: HomeScreen**

```kotlin
// ui/home/HomeScreen.kt
package com.kidguard.ui.home

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.kidguard.ui.navigation.Screen
import com.kidguard.ui.theme.ShieldGreen

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    navController: NavController,
    viewModel: HomeViewModel = hiltViewModel()
) {
    val children by viewModel.children.collectAsState()
    val settings by viewModel.settings.collectAsState()
    val isMonitoring by viewModel.isMonitoring.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("KidGuard 儿童守护") },
                actions = {
                    IconButton(onClick = { navController.navigate(Screen.Settings.route) }) {
                        Icon(Icons.Default.Settings, "设置")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 监控状态卡片
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = if (isMonitoring) ShieldGreen
                        else MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            imageVector = if (isMonitoring) Icons.Default.Shield
                            else Icons.Default.ShieldOutlined,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = if (isMonitoring) "守护中" else "已暂停",
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(
                            onClick = { viewModel.toggleMonitoring() },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(
                                if (isMonitoring) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = null
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(if (isMonitoring) "暂停监控" else "开始监控")
                        }
                    }
                }
            }

            // 已注册儿童列表
            item {
                Text(
                    text = "已注册儿童",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }

            if (children.isEmpty()) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { navController.navigate(Screen.AddChild.route) }
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.PersonAdd, contentDescription = null)
                            Spacer(modifier = Modifier.width(12.dp))
                            Text("添加第一个儿童")
                        }
                    }
                }
            } else {
                items(children) { child ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { navController.navigate(Screen.FaceEnroll.route.replace("{childId}", child.id.toString())) }
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.ChildCare, contentDescription = null)
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(child.name, fontWeight = FontWeight.Bold)
                                Text("${child.age}岁", style = MaterialTheme.typography.bodySmall)
                            }
                            Spacer(modifier = Modifier.weight(1f))
                            if (child.faceEmbedding != null) {
                                Icon(
                                    Icons.Default.CheckCircle,
                                    contentDescription = "已录入人脸",
                                    tint = ShieldGreen
                                )
                            } else {
                                Icon(
                                    Icons.Default.Warning,
                                    contentDescription = "未录入人脸",
                                    tint = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                }
            }

            // 快捷操作
            item {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "快捷操作",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = { navController.navigate(Screen.Children.route) },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.People, contentDescription = null)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("儿童管理")
                    }
                    OutlinedButton(
                        onClick = { navController.navigate(Screen.Logs.route) },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.History, contentDescription = null)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("锁定日志")
                    }
                }
            }
        }
    }
}
```

**Verification:** 首页显示正常，能导航到其他页面

---

## Task 9: 儿童管理页面

**Objective:** 实现添加儿童、人脸录入功能

**Files:**
- Create: `app/src/main/java/com/kidguard/ui/children/ChildrenScreen.kt`
- Create: `app/src/main/java/com/kidguard/ui/children/ChildrenViewModel.kt`
- Create: `app/src/main/java/com/kidguard/ui/children/AddChildScreen.kt`
- Create: `app/src/main/java/com/kidguard/ui/children/FaceEnrollScreen.kt`

（代码略，结构类似 HomeScreen，包含表单输入和 CameraX 预览）

**Verification:** 能添加儿童，能录入人脸

---

## Task 10: 设置页面

**Objective:** 实现监控参数配置

**Files:**
- Create: `app/src/main/java/com/kidguard/ui/settings/SettingsScreen.kt`
- Create: `app/src/main/java/com/kidguard/ui/settings/SettingsViewModel.kt`

（代码略，包含开关、滑块等设置项）

**Verification:** 能保存和读取设置

---

## Task 11: 日志页面

**Objective:** 实现锁定历史记录查看

**Files:**
- Create: `app/src/main/java/com/kidguard/ui/logs/LogsScreen.kt`
- Create: `app/src/main/java/com/kidguard/ui/logs/LogsViewModel.kt`

（代码略，显示锁定时间线）

**Verification:** 能查看历史锁定记录

---

## Task 12: 锁屏界面

**Objective:** 实现儿童看到的锁定界面

**Files:**
- Create: `app/src/main/java/com/kidguard/ui/lock/LockScreen.kt`

```kotlin
// ui/lock/LockScreen.kt
package com.kidguard.ui.lock

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun LockScreen(childName: String = "小朋友") {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.errorContainer
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = Icons.Default.Lock,
                contentDescription = null,
                modifier = Modifier.size(120.dp),
                tint = MaterialTheme.colorScheme.onErrorContainer
            )

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = "手机已锁定",
                fontSize = 32.sp,
                color = MaterialTheme.colorScheme.onErrorContainer
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "检测到儿童使用手机",
                fontSize = 18.sp,
                color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.7f)
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "请让家长来解锁",
                fontSize = 16.sp,
                color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.5f),
                textAlign = TextAlign.Center
            )
        }
    }
}
```

**Verification:** 锁屏界面显示正常

---

## 开发顺序

1. Task 1-3: 基础架构（项目、数据库、DI）
2. Task 4-5: 核心功能（人脸检测、锁屏）
3. Task 6: 后台服务
4. Task 7-8: UI 框架和首页
5. Task 9-11: 业务页面
6. Task 12: 锁屏界面

---

## 预估工时

| 阶段 | 任务 | 预估时间 |
|------|------|---------|
| 基础架构 | Task 1-3 | 2-3 小时 |
| 核心功能 | Task 4-5 | 3-4 小时 |
| 后台服务 | Task 6 | 1-2 小时 |
| UI 开发 | Task 7-12 | 4-6 小时 |
| 测试调优 | - | 2-3 小时 |
| **总计** | - | **12-18 小时** |

---

*计划完成于 2026-04-25*
