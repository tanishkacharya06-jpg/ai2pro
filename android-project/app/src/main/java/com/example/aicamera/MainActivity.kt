package com.example.aicamera

import android.Manifest
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Bundle
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
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
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

    fun initialize(context: Context) {
        val appContext = context.applicationContext
        prefs = appContext.getSharedPreferences("cfg", Context.MODE_PRIVATE)
        val masterKey = MasterKey.Builder(appContext).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        securePrefs = EncryptedSharedPreferences.create(
            appContext, "secure_auth", masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
        val legacySid = prefs.getString("sid", null)
        if (securePrefs.getString("sid", null).isNullOrEmpty() && !legacySid.isNullOrEmpty()) {
            securePrefs.edit().putString("sid", legacySid).apply()
        }
        prefs.edit().remove("sid").apply()
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
        get() = securePrefs.getString("sid", "") ?: ""
        set(v) { securePrefs.edit().putString("sid", v).apply() }
    var deviceId: String
        get() = securePrefs.getString("device_id", "") ?: ""
        set(v) { securePrefs.edit().putString("device_id", v).apply() }
    var installationId: String
        get() {
            val existing = securePrefs.getString("installation_id", null)
            if (!existing.isNullOrBlank()) return existing
            val created = UUID.randomUUID().toString()
            securePrefs.edit().putString("installation_id", created).commit()
            return created
        }
    var user: String
        get() = rd("user")
        set(v) = wr("user", v)
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

    /** Creates or restores an anonymous device profile; no website login or pairing is needed. */
    suspend fun connectGuest(): String = withContext(Dispatchers.IO) {
        if (base.isBlank()) resolve()
        if (sid.isNotBlank()) {
            try {
                val existingName = validate()
                // Do not keep a legacy website login attached to the camera-only app.
                if (existingName.startsWith("guest_")) return@withContext existingName
            } catch (_: Exception) { }
            sid = ""
            deviceId = ""
            user = ""
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
        .pointerInput(frontFacing) {
            detectTapGestures { position ->
                val view = previewView
                val camera = boundCamera
                if (view != null && camera != null) {
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

@Composable
private fun PhotoViewer(
    files: List<File>,
    startFile: File,
    onClose: () -> Unit,
    onEnhance: (File) -> Unit,
    onRevert: (File) -> Unit
) {
    val context = LocalContext.current
    var index by remember(files, startFile) { mutableIntStateOf(files.indexOf(startFile).coerceAtLeast(0)) }
    val current = files.getOrNull(index) ?: startFile
    val bitmap = rememberPhotoBitmap(current, 1)
    val canRevert = remember(current) { GalleryStorage.hasOriginalBackup(context, current) }
    var scale by remember(current) { mutableFloatStateOf(1f) }
    var offset by remember(current) { mutableStateOf(Offset.Zero) }

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
                    TextButton(onClick = { onEnhance(current); onClose() }) { Text("Enhance", color = Color.White) }
                    if (canRevert) TextButton(onClick = { onRevert(current); onClose() }) {
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
}

@Composable
private fun EnhancementQueueScreen(
    items: List<WorkInfo>,
    onBack: () -> Unit,
    onRetry: (WorkInfo) -> Unit
) {
    Column(Modifier.fillMaxSize().background(Color(0xFF120B1F)).padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("‹ Camera") }
            Spacer(Modifier.weight(1f))
            Text("Enhancement queue", color = Color.White, style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.weight(1f))
            Spacer(Modifier.width(44.dp))
        }
        Text("Tasks run one at a time and resume after an app restart.", color = Color.LightGray,
            style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 14.dp))
        if (items.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Your queue is empty", color = Color.Gray) }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                itemsIndexed(items, key = { _, info -> info.id.toString() }) { _, info ->
                    val taskOutcome = info.outputData.getString(KEY_TASK_STATUS).orEmpty()
                    val taskFailed = taskOutcome == "failed" || info.state == WorkInfo.State.FAILED
                    val status = when {
                        taskFailed -> "Failed"
                        taskOutcome == "completed" -> "Completed"
                        info.state == WorkInfo.State.ENQUEUED || info.state == WorkInfo.State.BLOCKED -> "Pending"
                        info.state == WorkInfo.State.RUNNING -> "Processing"
                        info.state == WorkInfo.State.CANCELLED -> "Cancelled"
                        info.state == WorkInfo.State.SUCCEEDED -> "Completed"
                        else -> "Pending"
                    }
                    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF281E34))) {
                        Column(Modifier.fillMaxWidth().padding(14.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Photo enhancement", color = Color.White, style = MaterialTheme.typography.titleMedium)
                                Spacer(Modifier.weight(1f))
                                Text(status, color = if (taskFailed) Color(0xFFFF8797) else Color(0xFFFFB5CE))
                            }
                            val stage = info.progress.getString(KEY_STAGE).orEmpty()
                            if (info.state == WorkInfo.State.RUNNING) {
                                Spacer(Modifier.height(8.dp))
                                LinearProgressIndicator(Modifier.fillMaxWidth())
                                if (stage.isNotBlank()) Text(stage, color = Color.LightGray, style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(top = 6.dp))
                            }
                            if (taskFailed) {
                                val error = info.outputData.getString(KEY_ERROR).orEmpty()
                                if (error.isNotBlank()) Text(error, color = Color(0xFFFFAAB6), style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(top = 6.dp))
                                TextButton(onClick = { onRetry(info) }) { Text("Retry") }
                            }
                            if (taskOutcome == "completed" || info.state == WorkInfo.State.SUCCEEDED) {
                                Text("Enhanced image saved to the gallery", color = Color.LightGray, style = MaterialTheme.typography.bodySmall,
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
    var captureRunning by remember { mutableStateOf(false) }
    var galleryFiles by remember { mutableStateOf(listOf<File>()) }
    var viewerFiles by remember { mutableStateOf<List<File>?>(null) }
    var viewerStart by remember { mutableStateOf<File?>(null) }
    var workInfos by remember { mutableStateOf(listOf<WorkInfo>()) }
    var message by remember { mutableStateOf("") }
    var flashEnabled by remember { mutableStateOf(false) }
    var settingsOpen by remember { mutableStateOf(false) }
    var serverOnline by remember { mutableStateOf<Boolean?>(null) }
    var serverStatus by remember { mutableStateOf("Checking server…") }
    var checkingServer by remember { mutableStateOf(false) }
    var timerSeconds by remember { mutableIntStateOf(Api.prefs.getInt("camera_timer", 0)) }
    var gridMode by remember { mutableIntStateOf(Api.prefs.getInt("camera_grid", 0)) }

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
                    Api.connectGuest()
                    prompts = Api.prompts()
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
                if (enhance) {
                    GalleryStorage.ensureOriginalBackup(context, saved)
                    enqueueEnhancement(context, saved, selectedPrompt)
                    Toast.makeText(context, "Original saved · added to enhancement queue", Toast.LENGTH_SHORT).show()
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

    when (page) {
        MainPage.CAMERA -> {
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                if (granted) {
                    CameraPreview(
                        capture = capture,
                        frontFacing = cameraFront,
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
                if (message.isNotBlank()) {
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
                    Spacer(Modifier.height(14.dp))
                    Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween) {
                        OutlinedButton(onClick = { page = MainPage.GALLERY }) { Text("Gallery") }
                        Box(Modifier.size(76.dp).border(4.dp, Color.White, CircleShape).padding(7.dp)
                            .background(if (captureRunning) Color.Gray else Color.White, CircleShape)
                            .clickable(enabled = granted && !captureRunning) { shoot() })
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            TextButton(onClick = { page = MainPage.QUEUE }) {
                                Text("Queue ${workInfos.count { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.BLOCKED || it.state == WorkInfo.State.RUNNING }}")
                            }
                            Text("${workInfos.count { it.state == WorkInfo.State.SUCCEEDED }} completed", color = Color.LightGray,
                                style = MaterialTheme.typography.labelSmall)
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
                                GalleryStorage.ensureOriginalBackup(context, file)
                                enqueueEnhancement(context, file, selectedPrompt)
                                Toast.makeText(context, "Added to enhancement queue", Toast.LENGTH_SHORT).show()
                                page = MainPage.QUEUE
                            } catch (e: Exception) { message = e.message ?: "Unable to queue enhancement" }
                        }
                    },
                    onRevert = { file ->
                        scope.launch {
                            try {
                                withContext(Dispatchers.IO) { GalleryStorage.revertOriginal(context, file) }
                                galleryFiles = withContext(Dispatchers.IO) { GalleryStorage.list(context) }
                                Toast.makeText(context, "Original photo restored", Toast.LENGTH_SHORT).show()
                            } catch (e: Exception) { message = e.message ?: "Unable to restore original" }
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
                            enqueueEnhancement(context, file, metadata.promptId, metadata.requestId)
                            Toast.makeText(context, "Task added to queue", Toast.LENGTH_SHORT).show()
                        } catch (e: Exception) { message = e.message ?: "Unable to retry task" }
                    } else message = "The original photo is missing, so this task cannot be retried"
                }
            )
        }
    }
    if (settingsOpen) {
        CameraSettingsDialog(
            accountName = Api.user,
            serverStatus = serverStatus,
            serverOnline = serverOnline,
            checkingServer = checkingServer,
            onRefreshServer = { refreshServer() },
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
    serverStatus: String,
    serverOnline: Boolean?,
    checkingServer: Boolean,
    onRefreshServer: () -> Unit,
    timerSeconds: Int,
    gridMode: Int,
    onTimerChange: (Int) -> Unit,
    onGridChange: (Int) -> Unit,
    onClose: () -> Unit,
    onDisconnect: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Camera settings") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Device profile", style = MaterialTheme.typography.labelMedium, color = Color.Gray)
                Text(accountName.ifBlank { "Camera-only mode · not connected yet" }, style = MaterialTheme.typography.titleMedium)
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
}

private fun Float.formatZoom(): String = if (this % 1f == 0f) this.toInt().toString() else String.format("%.1f", this)