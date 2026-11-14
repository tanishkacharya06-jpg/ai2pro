package com.example.aicamera

import android.Manifest
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas as BitmapCanvas
import android.graphics.Paint as BitmapPaint
import android.graphics.ColorMatrix as AndroidColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.net.Uri
import android.os.Bundle
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.LifecycleOwner
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

const val DEFAULT_LINK = "aicam-dc66661a0788dd75"   // must equal PAIR_CODE in the server's startup.bat

class AuthExpired : Exception("Session expired - please log in again")
class ApiHttpException(val statusCode: Int, message: String) : IOException(message) {
    val retryable: Boolean get() = statusCode == 408 || statusCode == 429 || statusCode >= 500
}

/** Talks to the existing laptop/server API. The request id lets retries resume a server-side job. */
object Api {
    private val http = OkHttpClient.Builder().connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS).writeTimeout(180, TimeUnit.SECONDS).build()
    private val quickHttp = OkHttpClient.Builder().connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS).writeTimeout(8, TimeUnit.SECONDS).build()
    lateinit var prefs: SharedPreferences
    private lateinit var securePrefs: SharedPreferences
    private const val KEY_ALIAS = "aicamera.device.credentials.v1"

    fun initialize(context: Context) {
        val appContext = context.applicationContext
        prefs = appContext.getSharedPreferences("cfg", Context.MODE_PRIVATE)
        securePrefs = appContext.getSharedPreferences("secure_auth_keystore_v1", Context.MODE_PRIVATE)
        // Remove the old unencrypted session field; device tokens are now stored only as
        // AES/GCM ciphertext protected by a non-exportable Android Keystore key.
        prefs.edit().remove("sid").apply()
    }

    private fun keystoreKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = store.getKey(KEY_ALIAS, null)
        if (existing is SecretKey) return existing
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .build()
        generator.init(spec)
        return generator.generateKey()
    }

    private fun readSecret(name: String): String {
        val encoded = securePrefs.getString(name, null) ?: return ""
        return try {
            val raw = Base64.decode(encoded, Base64.NO_WRAP)
            if (raw.size <= 12) return ""
            val iv = raw.copyOfRange(0, 12)
            val encrypted = raw.copyOfRange(12, raw.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, keystoreKey(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(encrypted), Charsets.UTF_8)
        } catch (_: Exception) {
            ""
        }
    }

    private fun writeSecret(name: String, value: String) {
        if (value.isEmpty()) {
            securePrefs.edit().remove(name).apply()
            return
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, keystoreKey())
        val encrypted = cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        securePrefs.edit().putString(name, Base64.encodeToString(encrypted, Base64.NO_WRAP)).apply()
    }

    private fun rd(k: String): String = prefs.getString(k, "") ?: ""
    private fun wr(k: String, v: String) { prefs.edit().putString(k, v).apply() }
    var link: String
        get() = rd("link").ifEmpty { DEFAULT_LINK }
        set(v) = wr("link", v)
    var base: String
        get() = rd("base")
        set(v) = wr("base", v)
    var sid: String
        get() = readSecret("sid")
        set(v) = writeSecret("sid", v)
    var deviceId: String
        get() = readSecret("device_id")
        set(v) = writeSecret("device_id", v)
    val installationId: String
        get() {
            val existing = readSecret("installation_id")
            if (existing.isNotBlank()) return existing
            val created = UUID.randomUUID().toString()
            writeSecret("installation_id", created)
            return created
        }
    var user: String
        get() = rd("user")
        set(v) = wr("user", v)
    var accountCode: String
        get() = readSecret("account_code")
        set(v) = writeSecret("account_code", v)
    var pid: Int
        get() = prefs.getInt("pid", 0)
        set(v) { prefs.edit().putInt("pid", v).apply() }

    private fun rq(path: String) = Request.Builder().url(base + path).header("Authorization", "Bearer $sid")
    private fun detail(r: Response): String =
        try { JSONObject(r.body!!.string()).optString("detail", "Error ${r.code}") } catch (e: Exception) { "Error ${r.code}" }

    private fun check(r: Response) {
        if (r.code == 401) throw AuthExpired()
        if (!r.isSuccessful) throw ApiHttpException(r.code, detail(r))
    }

    /** Pairing code -> latest public URL (published by the PC); typed http(s) URLs are used directly. */
    suspend fun resolve(): String = withContext(Dispatchers.IO) {
        val l = link.trim()
        if (l.startsWith("http")) {
            base = l.trimEnd('/')
        } else if (l.isNotEmpty()) {
            try {
                val text = quickHttp.newCall(Request.Builder().url("https://ntfy.sh/$l/json?poll=1&since=all").build())
                    .execute().use { it.body!!.string() }
                var latest = ""
                for (line in text.lines()) {
                    if (line.isBlank()) continue
                    val o = JSONObject(line)
                    if (o.optString("event") == "message" && o.optString("message").startsWith("http")) latest = o.getString("message")
                }
                if (latest.isNotEmpty()) base = latest.trim().trimEnd('/')
            } catch (_: Exception) { /* Keep the last known address. */ }
        }
        if (base.isEmpty()) error("Can't find your PC. Is the server running, and is the pairing code right?")
        base
    }

    suspend fun auth(mode: String, name: String, pass: String): String = withContext(Dispatchers.IO) {
        resolve()
        val body = JSONObject().put("name", name).put("password", pass).toString().toRequestBody("application/json".toMediaType())
        http.newCall(Request.Builder().url("$base/api/$mode").post(body).build()).execute().use { r ->
            if (!r.isSuccessful) throw ApiHttpException(r.code, detail(r))
            val c = r.headers("Set-Cookie").firstOrNull { it.startsWith("sid=") } ?: error("Login failed (no session)")
            sid = c.substringAfter("sid=").substringBefore(";")
            user = JSONObject(r.body!!.string()).getString("name")
            user
        }
    }

    suspend fun pair(code: String, deviceName: String = "Android device"): String = withContext(Dispatchers.IO) {
        resolve()
        val body = JSONObject().put("code", code).put("device_name", deviceName)
            .toString().toRequestBody("application/json".toMediaType())
        http.newCall(Request.Builder().url("$base/api/device/pair/complete").post(body).build()).execute().use { r ->
            if (!r.isSuccessful) throw ApiHttpException(r.code, detail(r))
            val json = JSONObject(r.body!!.string())
            sid = json.getString("device_token")
            deviceId = json.optString("device_id")
            user = json.getString("name")
            user
        }
    }


    suspend fun createOrRestoreProfile(): String = withContext(Dispatchers.IO) {
        resolve()
        val body = JSONObject().put("installation_id", installationId).put("device_name", "AI Camera Android")
            .toString().toRequestBody("application/json".toMediaType())
        http.newCall(Request.Builder().url("$base/api/device/profile").post(body).build()).execute().use { r ->
            if (!r.isSuccessful) throw ApiHttpException(r.code, detail(r))
            val json = JSONObject(r.body!!.string())
            sid = json.getString("device_token")
            deviceId = json.optString("device_id")
            user = json.getString("name")
            val code = json.optString("code")
            if (code.length == 4) accountCode = code
            user
        }
    }

    suspend fun loginWithCode(code: String): String = withContext(Dispatchers.IO) {
        val clean = code.trim()
        if (clean.length != 4 || !clean.all { it.isDigit() }) throw IOException("Enter all 4 digits.")
        resolve()
        val body = JSONObject().put("code", clean).put("installation_id", installationId)
            .put("device_name", "AI Camera Android").toString().toRequestBody("application/json".toMediaType())
        http.newCall(Request.Builder().url("$base/api/device/login-code").post(body).build()).execute().use { r ->
            if (!r.isSuccessful) throw ApiHttpException(r.code, detail(r))
            val json = JSONObject(r.body!!.string())
            sid = json.getString("device_token")
            deviceId = json.optString("device_id")
            user = json.getString("name")
            accountCode = clean
            user
        }
    }

    suspend fun regenerateAccountCode(): String = withContext(Dispatchers.IO) {
        http.newCall(rq("/api/device/code/regenerate").post("{}".toRequestBody("application/json".toMediaType())).build()).execute().use { r ->
            check(r)
            accountCode = JSONObject(r.body!!.string()).getString("code")
            accountCode
        }
    }

    private fun uploadLocalPhoto(file: File) {
        if (!file.isFile || sid.isBlank()) return
        val mime = when (file.extension.lowercase()) { "png" -> "image/png"; "webp" -> "image/webp"; else -> "image/jpeg" }
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("name", file.name)
            .addFormDataPart("modified", file.lastModified().toString())
            .addFormDataPart("photo", file.name, file.asRequestBody(mime.toMediaType())).build()
        http.newCall(rq("/api/gallery/upload").post(body).build()).execute().use { check(it) }
    }

    suspend fun syncGallery(context: Context, onProgress: suspend (Int, Int) -> Unit = { _, _ -> }): Int = withContext(Dispatchers.IO) {
        if (sid.isBlank() || user.isBlank()) return@withContext 0
        val index = http.newCall(rq("/api/gallery").get().build()).execute().use { r ->
            check(r)
            JSONArray(r.body!!.string())
        }
        val remote = mutableMapOf<String, Long>()
        for (i in 0 until index.length()) {
            val item = index.getJSONObject(i)
            remote[item.getString("name")] = item.optLong("modified", 0L)
        }
        val localFiles = GalleryStorage.list(context)
        var changed = 0
        val total = (localFiles.size + index.length()).coerceAtLeast(1)
        var completed = 0
        withContext(Dispatchers.Main.immediate) { onProgress(0, total) }
        for (file in localFiles) {
            val cloudModified = remote[file.name]
            if (cloudModified == null || file.lastModified() > cloudModified + 1000L) {
                uploadLocalPhoto(file)
                changed++
            }
            completed++
            withContext(Dispatchers.Main.immediate) { onProgress(completed, total) }
        }
        for (i in 0 until index.length()) {
            val item = index.getJSONObject(i)
            val name = item.getString("name")
            val modified = item.optLong("modified", 0L)
            val local = localFiles.firstOrNull { it.name == name }
            if (local == null || modified > local.lastModified() + 1000L) {
                val url = "$base/api/gallery/${Uri.encode(name)}"
                val temp = File(context.cacheDir, "cloud_${UUID.randomUUID()}_${File(name).name}")
                try {
                    http.newCall(Request.Builder().url(url).header("Authorization", "Bearer $sid").get().build()).execute().use { r ->
                        check(r)
                        temp.outputStream().use { out -> r.body!!.byteStream().copyTo(out) }
                    }
                    GalleryStorage.importRemote(context, temp, name, modified)
                    changed++
                } finally { temp.delete() }
            }
            completed++
            onProgress(completed, total)
        }
        changed
    }

    /** Legacy guest method retained for older builds. */
    suspend fun connectGuest(): String = withContext(Dispatchers.IO) {
        if (base.isBlank()) resolve()
        if (sid.isNotBlank()) {
            try { return@withContext validate() } catch (_: Exception) {
                sid = ""
                deviceId = ""
            }
        }
        val body = JSONObject()
            .put("installation_id", installationId)
            .put("device_name", "AI Camera Android")
            .toString().toRequestBody("application/json".toMediaType())
        quickHttp.newCall(Request.Builder().url("$base/api/device/guest").post(body).build()).execute().use { r ->
            if (!r.isSuccessful) throw ApiHttpException(r.code, detail(r))
            val json = JSONObject(r.body!!.string())
            sid = json.getString("device_token")
            deviceId = json.optString("device_id")
            user = json.getString("name")
            user
        }
    }

    suspend fun checkServerStatus(): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        try {
            val server = resolve()
            quickHttp.newCall(Request.Builder().url("$server/health").get().build()).execute().use { r ->
                if (!r.isSuccessful) throw IOException("Server returned HTTP ${r.code}")
                val json = JSONObject(r.body!!.string())
                val version = json.optString("version", "unknown")
                true to "Online · v$version"
            }
        } catch (e: Exception) {
            false to "Offline"
        }
    }

    suspend fun validate(): String = withContext(Dispatchers.IO) {
        if (sid.isBlank()) throw AuthExpired()
        http.newCall(rq("/api/device/me").get().build()).execute().use { r ->
            check(r)
            val json = JSONObject(r.body!!.string())
            user = json.getString("name")
            deviceId = json.optString("device_id")
            user
        }
    }

    suspend fun disconnect() = withContext(Dispatchers.IO) {
        val token = sid
        if (token.isNotBlank() && base.isNotBlank()) {
            runCatching {
                http.newCall(Request.Builder().url("$base/api/device/disconnect")
                    .header("Authorization", "Bearer $token")
                    .post("{}".toRequestBody("application/json".toMediaType())).build())
                    .execute().use { }
            }
        }
        sid = ""
        deviceId = ""
        user = ""
        accountCode = ""
    }

    suspend fun prompts(): List<Pair<Int, String>> = withContext(Dispatchers.IO) {
        val call = { http.newCall(rq("/api/prompts").build()).execute() }
        val r = try { call() } catch (e: IOException) { resolve(); call() }
        r.use {
            check(it)
            val a = JSONArray(it.body!!.string())
            (0 until a.length()).map { i -> a.getJSONObject(i).let { o -> o.getInt("id") to o.getString("name") } }
        }
    }

    private fun send(body: MultipartBody): String =
        http.newCall(rq("/enhance").post(body).build()).execute().use { r ->
            check(r)
            JSONObject(r.body!!.string()).getString("job_id")
        }

    /** Upload a photo, wait for the existing server engine, and download all returned result files. */
    suspend fun process(
        src: File,
        promptId: Int,
        outDir: File,
        requestId: String,
        stage: (String) -> Unit
    ): List<File> = withContext(Dispatchers.IO) {
        if (!src.isFile) throw IOException("The original photo is no longer available")
        val mime = when (src.extension.lowercase()) {
            "png" -> "image/png"
            "webp" -> "image/webp"
            else -> "image/jpeg"
        }
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("prompt_id", promptId.toString())
            .addFormDataPart("request_id", requestId)
            .addFormDataPart("photo", src.name, src.asRequestBody(mime.toMediaType())).build()
        stage("Uploading original photo")
        val id = try { send(body) } catch (e: IOException) { resolve(); send(body) }
        val started = System.currentTimeMillis()
        var count = 1
        while (true) {
            if (System.currentTimeMillis() - started > TimeUnit.MINUTES.toMillis(20)) {
                throw IOException("Enhancement timed out; the queued task can be retried")
            }
            delay(1500)
            val j = http.newCall(rq("/status/$id").build()).execute().use { r ->
                check(r)
                JSONObject(r.body!!.string())
            }
            val serverStage = j.optString("stage")
            stage((if (serverStage.isEmpty() || serverStage == "null") "Processing" else serverStage) +
                " · " + (System.currentTimeMillis() - started) / 1000 + "s")
            when (j.optString("status")) {
                "done" -> { count = j.optInt("count", 1).coerceIn(1, 8); break }
                "error" -> throw IllegalStateException(j.optString("error", "Enhancement failed"))
            }
        }
        stage("Downloading enhanced photo")
        val outputs = mutableListOf<File>()
        try {
            (1..count).forEach { n ->
                val f = File(outDir, "enhanced_${requestId}_$n.png")
                http.newCall(rq("/result/$id/$n").build()).execute().use { r ->
                    check(r)
                    f.outputStream().use { output -> r.body!!.byteStream().copyTo(output) }
                }
                outputs += f
            }
            // Do not delete the server job here. A worker restart can reuse its result until server TTL cleanup.
            outputs
        } catch (e: Exception) {
            outputs.forEach { it.delete() }
            throw e
        }
    }
}

fun loadBmp(file: File, sampleSize: Int = 1): Bitmap {
    val bitmap = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply {
        inSampleSize = sampleSize.coerceAtLeast(1)
    }) ?: throw IOException("Unable to decode image ${file.name}")
    val degrees = when (ExifInterface(file.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
        ExifInterface.ORIENTATION_ROTATE_90 -> 90f
        ExifInterface.ORIENTATION_ROTATE_180 -> 180f
        ExifInterface.ORIENTATION_ROTATE_270 -> 270f
        else -> 0f
    }
    return if (degrees == 0f) bitmap else Bitmap.createBitmap(
        bitmap, 0, 0, bitmap.width, bitmap.height, Matrix().apply { postRotate(degrees) }, true
    )
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Api.initialize(this)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFFFF8FB1), secondary = Color(0xFFA98BFF))) {
                Surface(Modifier.fillMaxSize(), color = Color(0xFF120B1F)) { Root() }
            }
        }
    }
}

@Composable
fun Root() {
    // The camera is the app's home screen. Server connection is checked in the background.
    MainScreen()
}

@Composable
fun Chips(items: List<Pair<Int, String>>, selected: Int, onPick: (Int) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items.forEach { (id, name) ->
            Button(onClick = { onPick(id) },
                colors = ButtonDefaults.buttonColors(containerColor = if (id == selected) Color(0xFFFF8FB1) else Color(0x665E526C))) {
                Text(name)
            }
        }
    }
}

/** Existing before/after comparison UI retained for compatibility with earlier result flows. */
@Composable
fun Compare(before: Bitmap, after: Bitmap, animate: Boolean) {
    val fraction = remember(after) { Animatable(if (animate) 1f else 0.5f) }
    var stopped by remember(after) { mutableStateOf(false) }
    var width by remember { mutableStateOf(1) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(after) {
        if (animate) {
            delay(300)
            if (!stopped) fraction.animateTo(0f, tween(1600, easing = FastOutSlowInEasing))
            if (!stopped) fraction.animateTo(0.5f, tween(700))
        }
    }
    Box(Modifier.fillMaxSize().padding(top = 24.dp, bottom = 110.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.aspectRatio(after.width.toFloat() / after.height).onSizeChanged { width = it.width }
            .pointerInput(after) {
                detectHorizontalDragGestures(onDragStart = { stopped = true }) { change, _ ->
                    change.consume()
                    scope.launch { fraction.snapTo((change.position.x / size.width).coerceIn(0f, 1f)) }
                }
            }) {
            Image(after.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            Image(before.asImageBitmap(), null,
                Modifier.fillMaxSize().drawWithContent { clipRect(right = size.width * fraction.value) { this@drawWithContent.drawContent() } },
                contentScale = ContentScale.Crop)
            Box(Modifier.fillMaxHeight().width(3.dp).offset { IntOffset((width * fraction.value).roundToInt() - 4, 0) }.background(Color.White))
            Text("BEFORE", Modifier.align(Alignment.TopStart).padding(10.dp), color = Color.White, style = MaterialTheme.typography.labelSmall)
            Text("AFTER", Modifier.align(Alignment.TopEnd).padding(10.dp), color = Color.White, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun rememberPhotoBitmap(file: File, sampleSize: Int = 1): Bitmap? {
    val bitmap by produceState<Bitmap?>(initialValue = null, file, sampleSize) {
        value = withContext(Dispatchers.IO) { runCatching { loadBmp(file, sampleSize) }.getOrNull() }
    }
    return bitmap
}

@Composable
private fun CameraPreview(
    capture: ImageCapture,
    frontFacing: Boolean,
    focusMode: String,
    onCameraReady: (Camera?, Float) -> Unit,
    onFacingFallback: () -> Unit,
    onZoomGesture: (Float) -> Unit,
    onError: (String) -> Unit
) {
    val context = LocalContext.current
    var previewView by remember { mutableStateOf<PreviewView?>(null) }
    var provider by remember { mutableStateOf<ProcessCameraProvider?>(null) }

    var boundCamera by remember { mutableStateOf<Camera?>(null) }
    Box(Modifier.fillMaxSize()
        .pointerInput(frontFacing, focusMode) {
            detectTapGestures { position ->
                val view = previewView
                val camera = boundCamera
                if (focusMode == "Tap to Focus" && view != null && camera != null) {
                    runCatching {
                        val point = view.meteringPointFactory.createPoint(position.x, position.y)
                        camera.cameraControl.startFocusAndMetering(FocusMeteringAction.Builder(point).build())
                    }
                }
            }
        }
        .pointerInput(frontFacing) {
            detectTransformGestures { _, _, zoomChange, _ ->
                if (zoomChange != 1f) onZoomGesture(zoomChange)
            }
        }
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { c -> PreviewView(c).also { previewView = it } },
            update = { previewView = it }
        )
    }

    LaunchedEffect(previewView, frontFacing) {
        val view = previewView ?: return@LaunchedEffect
        try {
            val cameraProvider = withContext(Dispatchers.IO) {
                ProcessCameraProvider.getInstance(context).get()
            }
            provider = cameraProvider
            val wanted = if (frontFacing) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
            val fallback = if (frontFacing) CameraSelector.DEFAULT_BACK_CAMERA else CameraSelector.DEFAULT_FRONT_CAMERA
            val selected = when {
                cameraProvider.hasCamera(wanted) -> wanted
                cameraProvider.hasCamera(fallback) -> {
                    onFacingFallback()
                    onError(if (frontFacing) "Front camera is unavailable. Using the rear camera." else "Rear camera is unavailable. Using the front camera.")
                    fallback
                }
                else -> throw IllegalStateException("This device has no available camera")
            }
            withContext(Dispatchers.Main.immediate) {
                cameraProvider.unbindAll()
                val preview = Preview.Builder().build().also { it.setSurfaceProvider(view.surfaceProvider) }
                val camera = cameraProvider.bindToLifecycle(context as LifecycleOwner, selected, preview, capture)
                boundCamera = camera
                val maxZoom = camera.cameraInfo.zoomState.value?.maxZoomRatio ?: 1f
                onCameraReady(camera, maxZoom.coerceAtLeast(1f))
            }
        } catch (e: Exception) {
            onCameraReady(null, 1f)
            onError(e.message ?: "Unable to start camera preview")
        }
    }

    LaunchedEffect(focusMode, previewView, boundCamera) {
        if (focusMode == "Auto Focus") {
            val view = previewView
            val camera = boundCamera
            if (view != null && camera != null && view.width > 0 && view.height > 0) {
                runCatching {
                    val center = view.meteringPointFactory.createPoint(view.width / 2f, view.height / 2f)
                    camera.cameraControl.startFocusAndMetering(FocusMeteringAction.Builder(center).build())
                }
            }
        }
    }

    DisposableEffect(previewView) {
        onDispose {
            provider?.unbindAll()
            boundCamera = null
            onCameraReady(null, 1f)
        }
    }
}

@Composable
private fun CapturePreviewDialog(
    file: File,
    busy: Boolean,
    onCancel: () -> Unit,
    onSave: () -> Unit,
    onSaveAndEnhance: () -> Unit
) {
    val bitmap = rememberPhotoBitmap(file, 2)
    Dialog(onDismissRequest = { if (!busy) onCancel() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.94f).fillMaxHeight(0.86f),
            color = Color(0xFF21172D),
            shape = RoundedCornerShape(24.dp)
        ) {
            Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Photo preview", style = MaterialTheme.typography.titleLarge, color = Color.White)
                Spacer(Modifier.height(12.dp))
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    if (bitmap != null) Image(bitmap.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                    else CircularProgressIndicator()
                }
                Spacer(Modifier.height(14.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(modifier = Modifier.weight(1f), enabled = !busy, onClick = onCancel) { Text("CANCEL") }
                    Button(modifier = Modifier.weight(1f), enabled = !busy, onClick = onSave) { Text("SAVE") }
                }
                Spacer(Modifier.height(8.dp))
                Button(modifier = Modifier.fillMaxWidth(), enabled = !busy, onClick = onSaveAndEnhance) {
                    Text("SAVE + ENHANCE")
                }
            }
        }
    }
}

@Composable
private fun GalleryScreen(
    files: List<File>,
    onBack: () -> Unit,
    onImport: () -> Unit,
    onOpen: (File) -> Unit
) {
    Column(Modifier.fillMaxSize().background(Color(0xFF120B1F)).padding(horizontal = 14.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("‹ Camera") }
            Spacer(Modifier.weight(1f))
            Text("Gallery", style = MaterialTheme.typography.titleLarge, color = Color.White)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onImport) { Text("Import") }
        }
        Text("${files.size} photos · newest first", color = Color.LightGray, style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(bottom = 12.dp))
        if (files.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No saved photos yet", color = Color.Gray)
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(112.dp),
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                itemsIndexed(files, key = { _, file -> file.name }) { _, file ->
                    val bitmap = rememberPhotoBitmap(file, 4)
                    Surface(
                        modifier = Modifier.fillMaxWidth().aspectRatio(1f).clickable { onOpen(file) },
                        shape = RoundedCornerShape(12.dp),
                        color = Color(0xFF2B2037)
                    ) {
                        if (bitmap != null) Image(bitmap.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                        else Box(contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    }
                }
            }
        }
    }
}

private fun copyBitmap(source: Bitmap): Bitmap = source.copy(Bitmap.Config.ARGB_8888, true)

private fun transformBitmap(
    source: Bitmap,
    brightness: Float,
    contrast: Float,
    saturation: Float,
    sharpness: Float,
    filter: String
): Bitmap {
    val output = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
    val matrix = AndroidColorMatrix()
    val sat = saturation.coerceIn(0f, 2f)
    val saturationMatrix = AndroidColorMatrix().apply { setSaturation(sat) }
    val c = contrast.coerceIn(0.25f, 2.5f)
    val b = brightness.coerceIn(-100f, 100f)
    val translate = 128f * (1f - c) + b
    val contrastMatrix = AndroidColorMatrix(floatArrayOf(
        c,0f,0f,0f,translate, 0f,c,0f,0f,translate, 0f,0f,c,0f,translate, 0f,0f,0f,1f,0f
    ))
    matrix.setConcat(contrastMatrix, saturationMatrix)
    when (filter) {
        "Mono" -> matrix.postConcat(AndroidColorMatrix().apply { setSaturation(0f) })
        "Warm" -> matrix.postConcat(AndroidColorMatrix(floatArrayOf(
            1.08f,0f,0f,0f,8f, 0f,1.01f,0f,0f,2f, 0f,0f,0.88f,0f,-5f, 0f,0f,0f,1f,0f
        )))
        "Cool" -> matrix.postConcat(AndroidColorMatrix(floatArrayOf(
            0.9f,0f,0f,0f,-4f, 0f,1f,0f,0f,0f, 0f,0f,1.12f,0f,8f, 0f,0f,0f,1f,0f
        )))
        "Vintage" -> matrix.postConcat(AndroidColorMatrix(floatArrayOf(
            0.9f,0f,0f,0f,18f, 0f,0.86f,0f,0f,10f, 0f,0f,0.72f,0f,0f, 0f,0f,0f,1f,0f
        )))
    }
    val paint = BitmapPaint(BitmapPaint.ANTI_ALIAS_FLAG).apply { colorFilter = ColorMatrixColorFilter(matrix) }
    BitmapCanvas(output).drawBitmap(source, 0f, 0f, paint)
    if (sharpness <= 0.01f) return output
    val amount = sharpness.coerceIn(0f, 2f)
    val w = output.width; val h = output.height
    if (w < 3 || h < 3) return output
    val src = IntArray(w * h); val dst = IntArray(w * h)
    output.getPixels(src, 0, w, 0, 0, w, h)
    src.copyInto(dst)
    for (y in 1 until h - 1) for (x in 1 until w - 1) {
        val i = y * w + x
        val center = src[i]
        fun channel(shift: Int): Int {
            val value = ((center shr shift) and 255) * (1f + 4f * amount) -
                (((src[i-1] shr shift) and 255) + ((src[i+1] shr shift) and 255) +
                 ((src[i-w] shr shift) and 255) + ((src[i+w] shr shift) and 255)) * amount
            return value.toInt().coerceIn(0,255)
        }
        dst[i] = (center and -0x1000000) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }
    val sharpened = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    sharpened.setPixels(dst, 0, w, 0, 0, w, h)
    output.recycle()
    return sharpened
}

private fun rotateBitmap(source: Bitmap, degrees: Float): Bitmap {
    val matrix = Matrix().apply { postRotate(degrees) }
    return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
}

private fun flipBitmap(source: Bitmap): Bitmap {
    val matrix = Matrix().apply { preScale(-1f, 1f) }
    return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
}

private fun cropBitmap(source: Bitmap, ratio: String): Bitmap {
    val target = when (ratio) { "1:1" -> 1f; "4:5" -> 0.8f; "16:9" -> 16f/9f; else -> 0f }
    if (target == 0f) return copyBitmap(source)
    val current = source.width.toFloat() / source.height
    val width: Int; val height: Int
    if (current > target) { height = source.height; width = (height * target).toInt().coerceAtLeast(1) }
    else { width = source.width; height = (width / target).toInt().coerceAtLeast(1) }
    return Bitmap.createBitmap(source, (source.width-width)/2, (source.height-height)/2, width, height)
}

private fun resizeBitmap(source: Bitmap, percent: Int): Bitmap {
    val scale = percent.coerceIn(25, 200) / 100f
    return Bitmap.createScaledBitmap(source, (source.width*scale).toInt().coerceAtLeast(1),
        (source.height*scale).toInt().coerceAtLeast(1), true)
}

@Composable
private fun PhotoEditor(file: File, onClose: () -> Unit, onSaved: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var working by remember(file) { mutableStateOf<Bitmap?>(null) }
    val undo = remember(file) { mutableStateListOf<Bitmap>() }
    val redo = remember(file) { mutableStateListOf<Bitmap>() }
    var brightness by remember(file) { mutableFloatStateOf(0f) }
    var contrast by remember(file) { mutableFloatStateOf(1f) }
    var saturation by remember(file) { mutableFloatStateOf(1f) }
    var sharpness by remember(file) { mutableFloatStateOf(0f) }
    var filter by remember(file) { mutableStateOf("Original") }
    var cropRatio by remember(file) { mutableStateOf("Original") }
    var resizePercent by remember(file) { mutableIntStateOf(100) }
    var saving by remember { mutableStateOf(false) }
    var processing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }

    LaunchedEffect(file) {
        working = withContext(Dispatchers.IO) {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            val maxDimension = maxOf(bounds.outWidth, bounds.outHeight)
            var sample = 1
            while (maxDimension / sample > 3072) sample *= 2
            BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
        }
    }
    fun commit(next: Bitmap) {
        working?.let { undo.add(it); if (undo.size > 3) undo.removeAt(0) }
        redo.clear()
        working = next
    }
    fun performTransform(transform: (Bitmap) -> Bitmap) {
        val source = working ?: return
        if (processing || saving) return
        scope.launch {
            processing = true
            try {
                val next = withContext(Dispatchers.IO) { transform(source) }
                commit(next)
            } catch (e: Exception) { error = e.message ?: "Could not apply edit" }
            finally { processing = false }
        }
    }
    fun applyAdjustments() {
        val b = brightness; val cValue = contrast; val s = saturation; val sh = sharpness; val selectedFilter = filter
        performTransform { transformBitmap(it, b, cValue, s, sh, selectedFilter) }
    }

    Dialog(onDismissRequest = { if (!saving) onClose() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(Color(0xFF130D1D)).padding(14.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onClose, enabled = !saving) { Text("Cancel") }
                Spacer(Modifier.weight(1f))
                Text("Photo editor", color = Color.White, style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = {
                    val current = working ?: return@TextButton
                    scope.launch {
                        saving = true
                        try {
                            withContext(Dispatchers.IO) {
                                val temp = File(context.cacheDir, "edit_${System.nanoTime()}.jpg")
                                temp.outputStream().use { out ->
                                    if (!current.compress(Bitmap.CompressFormat.JPEG, 95, out)) throw IOException("Could not encode edited photo")
                                }
                                GalleryStorage.replacePrimary(context, file, temp)
                                temp.delete()
                            }
                            Toast.makeText(context, "Edited photo saved", Toast.LENGTH_SHORT).show()
                            onSaved()
                        } catch (e: Exception) { error = e.message ?: "Unable to save edits" }
                        finally { saving = false }
                    }
                }, enabled = working != null && !saving && !processing) { Text(if (saving) "Saving…" else "Save") }
            }
            if (working == null) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            } else {
                Image(working!!.asImageBitmap(), null, Modifier.fillMaxWidth().weight(1f).padding(vertical = 8.dp),
                    contentScale = ContentScale.Fit)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { if (undo.isNotEmpty()) { redo.add(working!!); working = undo.removeAt(undo.lastIndex) } },
                        enabled = undo.isNotEmpty() && !processing) { Text("↶ Undo") }
                    OutlinedButton(onClick = { if (redo.isNotEmpty()) { undo.add(working!!); working = redo.removeAt(redo.lastIndex) } },
                        enabled = redo.isNotEmpty() && !processing) { Text("↷ Redo") }
                    Spacer(Modifier.weight(1f))
                    Text("${working!!.width} × ${working!!.height}", color = Color.LightGray, modifier = Modifier.align(Alignment.CenterVertically))
                }
                LazyColumn(Modifier.weight(1.15f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    item {
                        Text("Transform", color = Color.White, style = MaterialTheme.typography.titleMedium)
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            OutlinedButton(enabled = !processing, onClick = { performTransform { rotateBitmap(it, -90f) } }) { Text("Rotate ↶") }
                            OutlinedButton(enabled = !processing, onClick = { performTransform { rotateBitmap(it, 90f) } }) { Text("Rotate ↷") }
                            OutlinedButton(enabled = !processing, onClick = { performTransform { flipBitmap(it) } }) { Text("Flip") }
                        }
                        Text("Crop ratio", color = Color.LightGray)
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf("Original", "1:1", "4:5", "16:9").forEach { ratio ->
                                FilterChip(selected = cropRatio == ratio, onClick = { cropRatio = ratio }, label = { Text(ratio) })
                            }
                            Button(onClick = { performTransform { cropBitmap(it, cropRatio) } }, enabled = cropRatio != "Original" && !processing) { Text("Crop") }
                        }
                        Text("Resize", color = Color.LightGray)
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf(50, 75, 100, 125, 150, 200).forEach { value ->
                                FilterChip(selected = resizePercent == value, onClick = { resizePercent = value }, label = { Text("$value%") })
                            }
                            Button(onClick = { performTransform { resizeBitmap(it, resizePercent) } }, enabled = !processing) { Text("Apply") }
                        }
                    }
                    item {
                        Text("Adjustments", color = Color.White, style = MaterialTheme.typography.titleMedium)
                        Text("Brightness · ${brightness.toInt()}", color = Color.LightGray)
                        Slider(value = brightness, onValueChange = { brightness = it }, valueRange = -80f..80f)
                        Text("Contrast · ${"%.2f".format(contrast)}", color = Color.LightGray)
                        Slider(value = contrast, onValueChange = { contrast = it }, valueRange = 0.5f..1.8f)
                        Text("Saturation · ${"%.2f".format(saturation)}", color = Color.LightGray)
                        Slider(value = saturation, onValueChange = { saturation = it }, valueRange = 0f..2f)
                        Text("Sharpness · ${"%.2f".format(sharpness)}", color = Color.LightGray)
                        Slider(value = sharpness, onValueChange = { sharpness = it }, valueRange = 0f..1.5f)
                        Button(onClick = { applyAdjustments() }, enabled = !processing) { Text("Apply adjustments") }
                    }
                    item {
                        Text("Filters", color = Color.White, style = MaterialTheme.typography.titleMedium)
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf("Original", "Mono", "Warm", "Cool", "Vintage").forEach { name ->
                                FilterChip(selected = filter == name, onClick = { filter = name }, label = { Text(name) })
                            }
                            Button(onClick = { applyAdjustments() }, enabled = !processing) { Text("Apply filter") }
                        }
                    }
                }
            }
            if (error.isNotBlank()) Text(error, color = Color(0xFFFF8E9E), modifier = Modifier.padding(6.dp))
            if (saving || processing) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun PhotoViewer(
    files: List<File>,
    startFile: File,
    onClose: () -> Unit,
    onEnhance: (File) -> Unit,
    onRevert: (File) -> Unit,
    onEdited: () -> Unit
) {
    val context = LocalContext.current
    var editing by remember { mutableStateOf(false) }
    var index by remember(files, startFile) { mutableIntStateOf(files.indexOf(startFile).coerceAtLeast(0)) }
    val current = files.getOrNull(index) ?: startFile
    val bitmap = rememberPhotoBitmap(current, 1)
    val canRevert = remember(current) { GalleryStorage.hasOriginalBackup(context, current) }
    var scale by remember(current) { mutableFloatStateOf(1f) }
    var offset by remember(current) { mutableStateOf(Offset.Zero) }
    var confirmRevert by remember(current) { mutableStateOf(false) }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            if (bitmap != null) {
                Image(
                    bitmap.asImageBitmap(),
                    contentDescription = current.name,
                    modifier = Modifier.fillMaxSize()
                        .pointerInput(current) {
                            detectTransformGestures { _, pan, zoomChange, _ ->
                                val next = (scale * zoomChange).coerceIn(1f, 6f)
                                scale = next
                                offset = if (next <= 1.01f) Offset.Zero else offset + pan
                            }
                        }
                        .pointerInput(current) {
                            detectTapGestures(onDoubleTap = {
                                if (scale > 1.1f) {
                                    scale = 1f
                                    offset = Offset.Zero
                                } else {
                                    scale = 2.5f
                                    offset = Offset.Zero
                                }
                            })
                        }
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            translationX = offset.x
                            translationY = offset.y
                        },
                    contentScale = ContentScale.Fit
                )
            } else {
                CircularProgressIndicator(Modifier.align(Alignment.Center))
            }
            TextButton(onClick = onClose, modifier = Modifier.align(Alignment.TopStart).padding(10.dp)) {
                Text("✕ Close", color = Color.White)
            }
            Text("Pinch or double-tap to zoom", color = Color.White.copy(alpha = 0.75f),
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 20.dp))
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(12.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    TextButton(onClick = { editing = true }) { Text("Edit", color = Color.White) }
                    TextButton(onClick = { onEnhance(current); onClose() }) { Text("Enhance", color = Color.White) }
                    if (canRevert) TextButton(onClick = { confirmRevert = true }) {
                        Text("Revert original", color = Color.White)
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically) {
                    TextButton(enabled = index > 0, onClick = { index--; }) { Text("‹ Previous", color = Color.White) }
                    Text("${index + 1} / ${files.size}", color = Color.White)
                    TextButton(enabled = index < files.lastIndex, onClick = { index++; }) { Text("Next ›", color = Color.White) }
                }
            }
        }
    }
    if (editing) {
        PhotoEditor(file = current, onClose = { editing = false }, onSaved = { editing = false; onEdited() })
    }
    if (confirmRevert) {
        AlertDialog(
            onDismissRequest = { confirmRevert = false },
            title = { Text("Restore original photo?") },
            text = { Text("The current visible version will be replaced by the saved original. This cannot be undone from this screen.") },
            confirmButton = {
                TextButton(onClick = { confirmRevert = false; onRevert(current); onClose() }) { Text("Restore original") }
            },
            dismissButton = { TextButton(onClick = { confirmRevert = false }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun EnhancementQueueScreen(
    items: List<WorkInfo>, onBack: () -> Unit, onRetry: (WorkInfo) -> Unit,
    onCancel: (WorkInfo) -> Unit, onClearHistory: (List<WorkInfo>) -> Unit,
    onKeepOriginal: (WorkInfo) -> Unit, onKeepEnhanced: (WorkInfo) -> Unit,
    onKeepBoth: (WorkInfo) -> Unit
 ) {
    val context = LocalContext.current
    val history = items.filter { it.state == WorkInfo.State.SUCCEEDED || it.state == WorkInfo.State.FAILED || it.state == WorkInfo.State.CANCELLED }
    Column(Modifier.fillMaxSize().background(Color(0xFF120B1F)).padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("‹ Camera") }
            Spacer(Modifier.weight(1f))
            Text("Enhancement queue", color = Color.White, style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.weight(1f))
        }
        Text("Processing continues in the background. Completed photos wait here for your keep choice.",
            color = Color.LightGray, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 12.dp))
        if (history.isNotEmpty()) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("History (${history.size})", color = Color.White, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { onClearHistory(history) }) { Text("Clear History") }
            }
        }
        if (items.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("No active or completed tasks", color = Color.Gray) }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                itemsIndexed(items, key = { _, info -> info.id.toString() }) { _, info ->
                    val metadata = loadEnhancementTaskMetadata(context, info.id)
                    val sourceThumb = metadata?.let { rememberPhotoBitmap(File(it.sourceFile), 8) }
                    val taskOutcome = info.outputData.getString(KEY_TASK_STATUS).orEmpty()
                    val pendingPath = info.outputData.getString(KEY_PENDING_ENHANCED_FILE).orEmpty()
                    val pendingFile = pendingPath.takeIf { it.isNotBlank() }?.let(::File)
                    val enhancedThumb = pendingFile?.takeIf { it.isFile }?.let { rememberPhotoBitmap(it, 8) }
                    val hasPendingChoice = taskOutcome == "completed" && pendingFile?.isFile == true
                    val taskFailed = taskOutcome == "failed" || info.state == WorkInfo.State.FAILED
                    val status = when {
                        taskFailed -> "Failed"
                        hasPendingChoice -> "Choose version"
                        taskOutcome == "completed" -> "Completed"
                        info.state == WorkInfo.State.ENQUEUED || info.state == WorkInfo.State.BLOCKED -> "Waiting"
                        info.state == WorkInfo.State.RUNNING -> "Processing"
                        info.state == WorkInfo.State.CANCELLED -> "Cancelled"
                        info.state == WorkInfo.State.SUCCEEDED -> "Completed"
                        else -> "Pending"
                    }
                    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF281E34))) {
                        Column(Modifier.fillMaxWidth().padding(14.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (sourceThumb != null) {
                                    Image(sourceThumb.asImageBitmap(), null, Modifier.size(54.dp), contentScale = ContentScale.Crop)
                                    Spacer(Modifier.width(10.dp))
                                }
                                Column(Modifier.weight(1f)) {
                                    Text("Photo enhancement", color = Color.White, style = MaterialTheme.typography.titleMedium)
                                    metadata?.let { Text(File(it.sourceFile).name, color = Color.LightGray, style = MaterialTheme.typography.labelSmall, maxLines = 1) }
                                }
                                Text(status, color = if (taskFailed) Color(0xFFFF8797) else Color(0xFFFFB5CE))
                            }
                            val stage = info.progress.getString(KEY_STAGE).orEmpty()
                            if (info.state == WorkInfo.State.RUNNING) {
                                Spacer(Modifier.height(8.dp))
                                LinearProgressIndicator(Modifier.fillMaxWidth())
                                if (stage.isNotBlank()) Text(stage, color = Color.LightGray, style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(top = 6.dp))
                            } else if (info.state == WorkInfo.State.ENQUEUED || info.state == WorkInfo.State.BLOCKED) {
                                Text("Waiting for the current photo to finish", color = Color.LightGray,
                                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
                                TextButton(onClick = { onCancel(info) }) { Text("Remove from queue") }
                            }
                            if (taskFailed) {
                                val error = info.outputData.getString(KEY_ERROR).orEmpty()
                                if (error.isNotBlank()) Text(error, color = Color(0xFFFFAAB6), style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(top = 6.dp))
                                Row {
                                    TextButton(onClick = { onRetry(info) }) { Text("Retry") }
                                    TextButton(onClick = { onCancel(info) }) { Text("Remove") }
                                }
                            }
                            if (hasPendingChoice) {
                                Text("Enhanced preview · choose which version to keep", color = Color.White, modifier = Modifier.padding(top = 10.dp))
                                if (enhancedThumb != null) {
                                    Image(enhancedThumb.asImageBitmap(), null, Modifier.fillMaxWidth().height(170.dp),
                                        contentScale = ContentScale.Fit)
                                }
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    OutlinedButton(modifier = Modifier.weight(1f), onClick = { onKeepOriginal(info) }) { Text("Original") }
                                    Button(modifier = Modifier.weight(1f), onClick = { onKeepEnhanced(info) }) { Text("Enhanced") }
                                }
                                OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = { onKeepBoth(info) }) { Text("Original + Enhanced") }
                            } else if (taskOutcome == "completed" || info.state == WorkInfo.State.SUCCEEDED) {
                                Text("Saved to your gallery", color = Color.LightGray, style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(top = 6.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

private enum class MainPage { CAMERA, GALLERY, QUEUE }

@Composable
fun MainScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val capture = remember { ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY).build() }
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }

    var page by remember { mutableStateOf(MainPage.CAMERA) }
    var prompts by remember { mutableStateOf(listOf<Pair<Int, String>>()) }
    var selectedPrompt by remember { mutableIntStateOf(Api.pid) }
    var cameraFront by remember { mutableStateOf(false) }
    var activeCamera by remember { mutableStateOf<Camera?>(null) }
    var zoomRatio by remember { mutableFloatStateOf(1f) }
    var maxZoomRatio by remember { mutableFloatStateOf(1f) }
    var pendingPhoto by remember { mutableStateOf<File?>(null) }
    var savingPhoto by remember { mutableStateOf(false) }
    var applyingResult by remember { mutableStateOf(false) }
    var captureRunning by remember { mutableStateOf(false) }
    var galleryFiles by remember { mutableStateOf(listOf<File>()) }
    var viewerFiles by remember { mutableStateOf<List<File>?>(null) }
    var viewerStart by remember { mutableStateOf<File?>(null) }
    var workInfos by remember { mutableStateOf(listOf<WorkInfo>()) }
    var message by remember { mutableStateOf("") }
    var flashEnabled by remember { mutableStateOf(false) }
    var focusMode by remember { mutableStateOf(Api.prefs.getString("focus_mode", "Continuous Focus") ?: "Continuous Focus") }
    var focusMenuOpen by remember { mutableStateOf(false) }
    var settingsOpen by remember { mutableStateOf(false) }
    var serverOnline by remember { mutableStateOf<Boolean?>(null) }
    var serverStatus by remember { mutableStateOf("Checking server…") }
    var checkingServer by remember { mutableStateOf(false) }
    var timerSeconds by remember { mutableIntStateOf(Api.prefs.getInt("camera_timer", 0)) }
    var gridMode by remember { mutableIntStateOf(Api.prefs.getInt("camera_grid", 0)) }
    var accountName by remember { mutableStateOf(Api.user) }
    var accountCode by remember { mutableStateOf(Api.accountCode) }
    var accountBusy by remember { mutableStateOf(false) }
    var accountError by remember { mutableStateOf("") }
    var accountSwitchReady by remember { mutableStateOf(false) }
    var backupProgress by remember { mutableStateOf("") }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                try {
                    val extension = when (context.contentResolver.getType(uri)) {
                        "image/png" -> "png"
                        "image/webp" -> "webp"
                        else -> "jpg"
                    }
                    val imported = File(context.cacheDir, "import_${UUID.randomUUID()}.$extension")
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        imported.outputStream().use { output -> input.copyTo(output) }
                    } ?: throw IOException("Unable to read the selected photo")
                    pendingPhoto = imported
                } catch (e: Exception) {
                    message = e.message ?: "Could not import that photo"
                }
            }
        }
    }

    LaunchedEffect(granted) {
        if (!granted) permission.launch(Manifest.permission.CAMERA)
    }
    fun refreshServer() {
        if (checkingServer) return
        checkingServer = true
        scope.launch {
            val status = Api.checkServerStatus()
            serverOnline = status.first
            serverStatus = status.second
            if (status.first) {
                try {
                    if (Api.sid.isNotBlank()) {
                        try { Api.validate() }
                        catch (_: Exception) { Api.sid = ""; Api.deviceId = ""; Api.user = "" }
                    }
                    if (Api.sid.isBlank() || (Api.accountCode.isBlank() && Api.user.startsWith("guest_"))) {
                        Api.createOrRestoreProfile()
                    }
                    accountName = Api.user
                    accountCode = Api.accountCode
                    prompts = Api.prompts()
                    runCatching { Api.syncGallery(context) }
                    if (prompts.isNotEmpty() && prompts.none { it.first == selectedPrompt }) {
                        selectedPrompt = prompts[0].first
                        Api.pid = selectedPrompt
                    }
                } catch (e: Exception) {
                    message = "Server is online, but the app service is unavailable: ${e.message ?: "connection error"}"
                }
            } else {
                message = "Server offline · camera and local gallery are still available"
            }
            checkingServer = false
        }
    }

    LaunchedEffect(Unit) {
        refreshServer()
        while (isActive) {
            try {
                workInfos = withContext(Dispatchers.IO) {
                    WorkManager.getInstance(context).getWorkInfosByTag(ENHANCEMENT_WORK_TAG).get()
                        .sortedBy { loadEnhancementTaskMetadata(context, it.id)?.createdAt ?: 0L }
                }
            } catch (_: Exception) { }
            delay(1200)
        }
    }
    LaunchedEffect(page, viewerStart) {
        if (page == MainPage.GALLERY && viewerStart == null) {
            runCatching { Api.syncGallery(context) }
            galleryFiles = withContext(Dispatchers.IO) { GalleryStorage.list(context) }
            while (isActive) {
                galleryFiles = withContext(Dispatchers.IO) { GalleryStorage.list(context) }
                delay(1500)
            }
        }
    }
    LaunchedEffect(message) {
        if (message.isNotBlank()) {
            delay(4500)
            message = ""
        }
    }

    fun setZoom(target: Float) {
        val camera = activeCamera ?: return
        val next = target.coerceIn(1f, maxZoomRatio.coerceAtLeast(1f))
        zoomRatio = next
        try { camera.cameraControl.setZoomRatio(next) } catch (_: Exception) { }
    }

    fun shoot() {
        if (captureRunning || activeCamera == null) return
        captureRunning = true
        scope.launch {
            if (timerSeconds > 0) {
                for (remaining in timerSeconds downTo 1) {
                    message = "Photo in ${remaining}…"
                    delay(1000)
                }
            }
            val file = File(context.cacheDir, "capture_${UUID.randomUUID()}.jpg")
            capture.takePicture(
                ImageCapture.OutputFileOptions.Builder(file).build(),
                ContextCompat.getMainExecutor(context),
                object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                        captureRunning = false
                        message = ""
                        pendingPhoto = file
                    }
                    override fun onError(exception: ImageCaptureException) {
                        captureRunning = false
                        file.delete()
                        message = exception.message ?: "Photo capture failed"
                    }
                }
            )
        }
    }

    fun cancelPreview() {
        pendingPhoto?.delete()
        pendingPhoto = null
    }

    fun savePreview(enhance: Boolean) {
        val source = pendingPhoto ?: return
        if (savingPhoto) return
        savingPhoto = true
        scope.launch {
            try {
                val saved = withContext(Dispatchers.IO) { GalleryStorage.save(context, source, "original") }
                runCatching { Api.syncGallery(context) }
                if (enhance) {
                    enqueueEnhancement(context, saved, selectedPrompt)
                    Toast.makeText(context, "Original saved · enhancement running in background", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, "Original photo saved", Toast.LENGTH_SHORT).show()
                }
                source.delete()
                pendingPhoto = null
                if (page == MainPage.GALLERY) {
                    galleryFiles = withContext(Dispatchers.IO) { GalleryStorage.list(context) }
                }
            } catch (e: Exception) {
                message = e.message ?: "Unable to save photo"
            } finally {
                savingPhoto = false
            }
        }
    }



    fun showAccountCode() {
        if (accountBusy) return
        if (Api.accountCode.isNotBlank()) { accountCode = Api.accountCode; return }
        accountBusy = true
        accountError = ""
        scope.launch {
            try { accountCode = Api.regenerateAccountCode() }
            catch (e: Exception) { accountError = e.message ?: "Could not retrieve your account code" }
            finally { accountBusy = false }
        }
    }

    fun logoutForSwitch() {
        if (accountBusy) return
        val unresolved = workInfos.any { info ->
            info.state == WorkInfo.State.RUNNING || info.state == WorkInfo.State.ENQUEUED ||
                info.state == WorkInfo.State.BLOCKED ||
                info.outputData.getString(KEY_PENDING_ENHANCED_FILE)?.let { File(it).isFile } == true
        }
        if (unresolved) {
            accountError = "Finish or remove active enhancement tasks and choose the completed result before switching accounts."
            return
        }
        accountBusy = true
        accountError = ""
        scope.launch {
            try {
                Api.disconnect()
                accountName = ""
                accountCode = ""
                accountSwitchReady = true
                message = "Logged out. Enter the other account's 4-digit code."
            } catch (e: Exception) { accountError = e.message ?: "Could not log out" }
            finally { accountBusy = false }
        }
    }

    fun loginAnotherAccount(code: String) {
        if (accountBusy) return
        accountBusy = true
        accountError = ""
        scope.launch {
            try {
                Api.loginWithCode(code)
                accountName = Api.user
                accountCode = Api.accountCode
                accountSwitchReady = false
                backupProgress = "Backing up this account's gallery…"
                runCatching {
                    Api.syncGallery(context) { done, total -> backupProgress = "Backing up photos · $done/$total" }
                }
                backupProgress = ""
                galleryFiles = withContext(Dispatchers.IO) { GalleryStorage.list(context) }
                message = "Account connected. Cloud gallery synced."
            } catch (e: Exception) { accountError = e.message ?: "Unable to log in with that code" }
            finally { accountBusy = false }
        }
    }

    when (page) {
        MainPage.CAMERA -> {
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                if (granted) {
                    CameraPreview(
                        capture = capture,
                        frontFacing = cameraFront,
                        focusMode = focusMode,
                        onCameraReady = { camera, max ->
                            activeCamera = camera
                            maxZoomRatio = max
                            if (camera == null) zoomRatio = 1f
                        },
                        onFacingFallback = { cameraFront = false },
                        onZoomGesture = { factor -> setZoom(zoomRatio * factor) },
                        onError = { message = it }
                    )
                } else {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("Camera permission is required to capture photos", color = Color.White)
                        Button(onClick = { permission.launch(Manifest.permission.CAMERA) }, modifier = Modifier.align(Alignment.BottomCenter).padding(28.dp)) {
                            Text("Grant camera permission")
                        }
                    }
                }

                Row(Modifier.fillMaxWidth().align(Alignment.TopCenter).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { settingsOpen = true }) { Text("⚙ Settings", color = Color.White) }
                    Spacer(Modifier.width(4.dp))
                    Text("Normal Photo", color = Color.White, style = MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.width(4.dp))
                    Box {
                        TextButton(onClick = { focusMenuOpen = true }) { Text("Focus: $focusMode", color = Color.White) }
                        DropdownMenu(expanded = focusMenuOpen, onDismissRequest = { focusMenuOpen = false }) {
                            listOf("Auto Focus", "Continuous Focus", "Tap to Focus").forEach { option ->
                                DropdownMenuItem(text = { Text(option) }, onClick = {
                                    focusMode = option
                                    Api.prefs.edit().putString("focus_mode", option).apply()
                                    focusMenuOpen = false
                                })
                            }
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    val hasFlash = activeCamera?.cameraInfo?.hasFlashUnit() == true
                    TextButton(enabled = hasFlash, onClick = {
                        flashEnabled = !flashEnabled
                        activeCamera?.cameraControl?.enableTorch(flashEnabled)
                    }) { Text(if (!hasFlash) "No flash" else if (flashEnabled) "Flash ON" else "Flash OFF", color = Color.White) }
                    TextButton(onClick = { cameraFront = !cameraFront; zoomRatio = 1f; flashEnabled = false }) {
                        Text("⇄ Switch", color = Color.White)
                    }
                }
                if (gridMode > 0) {
                    Canvas(Modifier.fillMaxSize()) {
                        val divisions = when (gridMode) {
                            3 -> listOf(1f / 3f, 2f / 3f)
                            4 -> listOf(0.25f, 0.5f, 0.75f)
                            else -> listOf(0.382f, 0.618f)
                        }
                        divisions.forEach { fraction ->
                            drawLine(Color.White.copy(alpha = 0.35f), Offset(size.width * fraction, 0f),
                                Offset(size.width * fraction, size.height), 1.dp.toPx())
                            drawLine(Color.White.copy(alpha = 0.35f), Offset(0f, size.height * fraction),
                                Offset(size.width, size.height * fraction), 1.dp.toPx())
                        }
                    }
                }
                if (backupProgress.isNotBlank()) {
                    Text(backupProgress, color = Color.White, modifier = Modifier.align(Alignment.TopCenter).padding(top = 62.dp)
                        .background(Color(0xBB261B31), RoundedCornerShape(12.dp)).padding(horizontal = 14.dp, vertical = 8.dp))
                } else if (message.isNotBlank()) {
                    Text(message, color = Color.White, modifier = Modifier.align(Alignment.TopCenter).padding(top = 62.dp)
                        .background(Color(0xBB261B31), RoundedCornerShape(12.dp)).padding(horizontal = 14.dp, vertical = 8.dp))
                }

                Column(Modifier.align(Alignment.CenterEnd).padding(end = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("${zoomRatio.formatZoom()}×", color = Color.White, style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.background(Color(0x99000000), RoundedCornerShape(12.dp)).padding(horizontal = 10.dp, vertical = 6.dp))
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(enabled = zoomRatio > 1.01f, onClick = { setZoom(zoomRatio / 1.25f) }) { Text("−") }
                    Spacer(Modifier.height(6.dp))
                    OutlinedButton(enabled = zoomRatio < maxZoomRatio, onClick = { setZoom(zoomRatio * 1.25f) }) { Text("+") }
                }

                Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    .background(Color(0xA50E0916)).padding(bottom = 22.dp, top = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Chips(prompts, selectedPrompt) { selectedPrompt = it; Api.pid = it }
                    workInfos.firstOrNull { it.state == WorkInfo.State.RUNNING }?.let { activeTask ->
                        LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 18.dp))
                        Text(activeTask.progress.getString(KEY_STAGE).orEmpty().ifBlank { "Enhancing photo in background" },
                            color = Color(0xFFFFB5CE), style = MaterialTheme.typography.labelSmall)
                    }
                    Spacer(Modifier.height(14.dp))
                    Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween) {
                        OutlinedButton(onClick = { page = MainPage.GALLERY }) { Text("Gallery") }
                        Box(Modifier.size(76.dp).border(4.dp, Color.White, CircleShape).padding(7.dp)
                            .background(if (captureRunning) Color.Gray else Color.White, CircleShape)
                            .clickable(enabled = granted && !captureRunning) { shoot() })
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            val runningCount = workInfos.count { it.state == WorkInfo.State.RUNNING }
                            val waitingCount = workInfos.count { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.BLOCKED }
                            if (runningCount > 0 && waitingCount > 0) {
                                TextButton(onClick = { page = MainPage.QUEUE }) { Text("Queue · $waitingCount waiting") }
                            } else if (runningCount > 0) {
                                Text("Enhancing in background", color = Color(0xFFFFB5CE), style = MaterialTheme.typography.labelSmall)
                            } else if (waitingCount > 0) {
                                Text("$waitingCount waiting", color = Color.LightGray, style = MaterialTheme.typography.labelSmall)
                            }
                            if (workInfos.any { it.outputData.getString(KEY_PENDING_ENHANCED_FILE)?.let { path -> File(path).isFile } == true }) {
                                TextButton(onClick = { page = MainPage.QUEUE }) { Text("Review result") }
                            }
                            if (workInfos.any { it.state == WorkInfo.State.SUCCEEDED || it.state == WorkInfo.State.FAILED || it.state == WorkInfo.State.CANCELLED }) {
                                TextButton(onClick = { page = MainPage.QUEUE }) { Text("History") }
                            }
                        }
                    }
                }
            }
            pendingPhoto?.let { file ->
                CapturePreviewDialog(
                    file = file,
                    busy = false,
                    onCancel = { cancelPreview() },
                    onSave = { savePreview(false) },
                    onSaveAndEnhance = { savePreview(true) }
                )
            }
        }
        MainPage.GALLERY -> {
            GalleryScreen(
                files = galleryFiles,
                onBack = { page = MainPage.CAMERA },
                onImport = { picker.launch("image/*") },
                onOpen = { file -> viewerFiles = galleryFiles.toList(); viewerStart = file }
            )
            val snapshot = viewerFiles
            val start = viewerStart
            if (snapshot != null && start != null) {
                PhotoViewer(
                    files = snapshot,
                    startFile = start,
                    onClose = { viewerFiles = null; viewerStart = null },
                    onEnhance = { file ->
                        scope.launch {
                            try {
                                enqueueEnhancement(context, file, selectedPrompt)
                                Toast.makeText(context, "Enhancement started in background", Toast.LENGTH_SHORT).show()
                            } catch (e: Exception) { message = e.message ?: "Unable to queue enhancement" }
                        }
                    },
                    onRevert = { file ->
                        scope.launch {
                            try {
                                withContext(Dispatchers.IO) { GalleryStorage.revertOriginal(context, file) }
                                runCatching { Api.syncGallery(context) }
                                galleryFiles = withContext(Dispatchers.IO) { GalleryStorage.list(context) }
                                Toast.makeText(context, "Original photo restored", Toast.LENGTH_SHORT).show()
                            } catch (e: Exception) { message = e.message ?: "Unable to restore original" }
                        }
                    },
                    onEdited = {
                        scope.launch {
                            runCatching { Api.syncGallery(context) }
                            galleryFiles = withContext(Dispatchers.IO) { GalleryStorage.list(context) }
                        }
                    }
                )
            }
            pendingPhoto?.let { file ->
                CapturePreviewDialog(file, savingPhoto, { cancelPreview() }, { savePreview(false) }, { savePreview(true) })
            }
        }
        MainPage.QUEUE -> {
            EnhancementQueueScreen(
                items = workInfos,
                onBack = { page = MainPage.CAMERA },
                onRetry = { info ->
                    val metadata = loadEnhancementTaskMetadata(context, info.id)
                    val file = metadata?.let { File(it.sourceFile) }
                    if (file != null && file.isFile) {
                        try {
                            enqueueEnhancement(context, file, metadata.promptId, metadata.requestId, metadata.accountName)
                            Toast.makeText(context, "Task added to queue", Toast.LENGTH_SHORT).show()
                        } catch (e: Exception) { message = e.message ?: "Unable to retry task" }
                    } else message = "The original photo is missing, so this task cannot be retried"
                },
                onCancel = { info ->
                    info.outputData.getString(KEY_PENDING_ENHANCED_FILE)?.takeIf { it.isNotBlank() }?.let { GalleryStorage.discardPendingEnhancement(File(it)) }
                    WorkManager.getInstance(context).cancelWorkById(info.id)
                },
                onClearHistory = { history ->
                    WorkManager.getInstance(context).pruneWork()
                    val prefs = context.getSharedPreferences("enhancement_queue_metadata", Context.MODE_PRIVATE)
                    val edit = prefs.edit()
                    history.forEach { edit.remove("task_" + it.id) }
                    edit.apply()
                },
                onKeepOriginal = { info ->
                    info.outputData.getString(KEY_PENDING_ENHANCED_FILE)?.takeIf { it.isNotBlank() }?.let { GalleryStorage.discardPendingEnhancement(File(it)) }
                    Toast.makeText(context, "Original photo kept", Toast.LENGTH_SHORT).show()
                },
                onKeepEnhanced = { info ->
                    if (!applyingResult) {
                        applyingResult = true
                        val source = info.outputData.getString(KEY_SOURCE_FILE).orEmpty()
                        val pending = info.outputData.getString(KEY_PENDING_ENHANCED_FILE).orEmpty()
                        scope.launch {
                            try {
                                withContext(Dispatchers.IO) { GalleryStorage.applyPendingEnhancement(context, File(source), File(pending), false) }
                                runCatching { Api.syncGallery(context) }
                                galleryFiles = withContext(Dispatchers.IO) { GalleryStorage.list(context) }
                                Toast.makeText(context, "Enhanced photo saved", Toast.LENGTH_SHORT).show()
                            } catch (e: Exception) { message = e.message ?: "Could not save enhanced photo" }
                            finally { applyingResult = false }
                        }
                    }
                },
                onKeepBoth = { info ->
                    if (!applyingResult) {
                        applyingResult = true
                        val source = info.outputData.getString(KEY_SOURCE_FILE).orEmpty()
                        val pending = info.outputData.getString(KEY_PENDING_ENHANCED_FILE).orEmpty()
                        scope.launch {
                            try {
                                withContext(Dispatchers.IO) { GalleryStorage.applyPendingEnhancement(context, File(source), File(pending), true) }
                                runCatching { Api.syncGallery(context) }
                                galleryFiles = withContext(Dispatchers.IO) { GalleryStorage.list(context) }
                                Toast.makeText(context, "Enhanced photo saved; original kept for Revert", Toast.LENGTH_SHORT).show()
                            } catch (e: Exception) { message = e.message ?: "Could not save both versions" }
                            finally { applyingResult = false }
                        }
                    }
                }
            )
        }
    }
    if (settingsOpen) {
        CameraSettingsDialog(
            accountName = accountName,
            accountCode = accountCode,
            accountBusy = accountBusy,
            accountError = accountError,
            switchReady = accountSwitchReady,
            onShowCode = { showAccountCode() },
            onLogoutForSwitch = { logoutForSwitch() },
            onLoginCode = { loginAnotherAccount(it) },
            serverStatus = serverStatus,
            serverOnline = serverOnline,
            checkingServer = checkingServer,
            onRefreshServer = { refreshServer() },
            backupProgress = backupProgress,
            timerSeconds = timerSeconds,
            gridMode = gridMode,
            onTimerChange = {
                timerSeconds = it
                Api.prefs.edit().putInt("camera_timer", it).apply()
            },
            onGridChange = {
                gridMode = it
                Api.prefs.edit().putInt("camera_grid", it).apply()
            },
            onClose = { settingsOpen = false }
        )
    }
}

@Composable
private fun CameraSettingsDialog(
    accountName: String,
    accountCode: String,
    accountBusy: Boolean,
    accountError: String,
    switchReady: Boolean,
    onShowCode: () -> Unit,
    onLogoutForSwitch: () -> Unit,
    onLoginCode: (String) -> Unit,
    serverStatus: String,
    serverOnline: Boolean?,
    checkingServer: Boolean,
    onRefreshServer: () -> Unit,
    backupProgress: String,
    timerSeconds: Int,
    gridMode: Int,
    onTimerChange: (Int) -> Unit,
    onGridChange: (Int) -> Unit,
    onClose: () -> Unit
) {
    var showCodeDialog by remember { mutableStateOf(false) }
    var showLogoutWarning by remember { mutableStateOf(false) }
    var showLoginDialog by remember { mutableStateOf(false) }
    var savedCodeConfirmed by remember { mutableStateOf(false) }
    var enteredCode by remember { mutableStateOf("") }
    LaunchedEffect(switchReady) { if (switchReady) showLoginDialog = true }
    LaunchedEffect(accountName) {
        if (accountName.isNotBlank()) { showLoginDialog = false; enteredCode = "" }
    }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Camera settings") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("My Account", style = MaterialTheme.typography.labelMedium, color = Color.Gray)
                Text(accountName.ifBlank { "Creating your account when the server is online…" }, style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(enabled = !accountBusy, onClick = { showCodeDialog = true; onShowCode() }) { Text("Show Code") }
                    Button(enabled = !accountBusy, onClick = { savedCodeConfirmed = false; showLogoutWarning = true }) {
                        Text("Login Another Account")
                    }
                }
                if (accountError.isNotBlank()) Text(accountError, color = Color(0xFFFFAAB6), style = MaterialTheme.typography.bodySmall)
                if (backupProgress.isNotBlank()) {
                    Text(backupProgress, color = Color(0xFFFFB5CE), style = MaterialTheme.typography.bodySmall)
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                Text("Server status", style = MaterialTheme.typography.labelMedium, color = Color.Gray)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val statusColor = when (serverOnline) {
                        true -> Color(0xFF69D69A)
                        false -> Color(0xFFFF8797)
                        null -> Color.LightGray
                    }
                    Box(Modifier.size(9.dp).background(statusColor, CircleShape))
                    Spacer(Modifier.width(8.dp))
                    Text(serverStatus, color = statusColor, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.weight(1f))
                    TextButton(enabled = !checkingServer, onClick = onRefreshServer) {
                        Text(if (checkingServer) "Checking…" else "Refresh")
                    }
                }
                Text("Capture timer")
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    listOf(0, 3, 5, 10).forEach { seconds ->
                        TextButton(onClick = { onTimerChange(seconds) }) {
                            val label = if (seconds == 0) "Off" else "${seconds}s"
                            Text((if (timerSeconds == seconds) "✓ " else "") + label)
                        }
                    }
                }
                Text("Composition grid")
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    listOf(0, 3, 4, 5).forEach { mode ->
                        val label = when (mode) { 0 -> "Off"; 3 -> "3×3"; 4 -> "4×4"; else -> "Golden" }
                        TextButton(onClick = { onGridChange(mode) }) {
                            Text((if (gridMode == mode) "✓ " else "") + label)
                        }
                    }
                }
                Text("Tap the preview to focus. Flashlight is available only on devices with a flash.",
                    color = Color.Gray, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Done") } }
    )

    if (showCodeDialog) {
        AlertDialog(
            onDismissRequest = { showCodeDialog = false },
            title = { Text("Your account code") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Save this 4-digit code. It is the only information needed to log in to this account on another device.")
                    Text(if (accountCode.length == 4) accountCode else if (accountBusy) "Loading…" else "Code unavailable",
                        style = MaterialTheme.typography.headlineLarge, color = Color(0xFFFFB5CE))
                    Text("Keep this code private. Anyone with it can access your account gallery.",
                        color = Color.Gray, style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = { TextButton(onClick = { showCodeDialog = false }) { Text("Done") } }
        )
    }

    if (showLogoutWarning) {
        AlertDialog(
            onDismissRequest = { if (!accountBusy) showLogoutWarning = false },
            title = { Text("Save your code before logging out") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("You will need your current 4-digit code to return to this account and its backed-up photos.")
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = savedCodeConfirmed, onCheckedChange = { savedCodeConfirmed = it })
                        Text("I have saved my 4-digit code")
                    }
                }
            },
            confirmButton = {
                TextButton(enabled = savedCodeConfirmed && !accountBusy, onClick = {
                    showLogoutWarning = false
                    onLogoutForSwitch()
                }) { Text(if (accountBusy) "Logging out…" else "Confirm & Logout") }
            },
            dismissButton = { TextButton(enabled = !accountBusy, onClick = { showLogoutWarning = false }) { Text("Cancel") } }
        )
    }

    if (showLoginDialog) {
        AlertDialog(
            onDismissRequest = { if (!accountBusy) showLoginDialog = false },
            title = { Text("Login another account") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Enter that account's 4-digit code. Its cloud photos will appear in this device's gallery.")
                    OutlinedTextField(
                        value = enteredCode,
                        onValueChange = { value -> enteredCode = value.filter { it.isDigit() }.take(4) },
                        label = { Text("4-digit account code") },
                        singleLine = true
                    )
                    if (accountError.isNotBlank()) Text(accountError, color = Color(0xFFFFAAB6))
                }
            },
            confirmButton = {
                TextButton(enabled = enteredCode.length == 4 && !accountBusy, onClick = { onLoginCode(enteredCode) }) {
                    Text(if (accountBusy) "Connecting…" else "Login")
                }
            },
            dismissButton = { TextButton(enabled = !accountBusy, onClick = { showLoginDialog = false }) { Text("Cancel") } }
        )
    }
}

private fun Float.formatZoom(): String = if (this % 1f == 0f) this.toInt().toString() else String.format("%.1f", this)