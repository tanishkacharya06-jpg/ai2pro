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
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.animateDpAsState
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
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
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

const val DEFAULT_LINK = "aicam-dc66661a0788dd75"   // must equal PAIR_CODE in the server's startup.bat

data class PromptItem(val id: Int, val name: String, val text: String)

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
    var isAdmin: Boolean
        get() = prefs.getBoolean("admin_mode", false)
        set(v) { prefs.edit().putBoolean("admin_mode", v).apply() }
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


    suspend fun createOrRestoreProfile(newAccount: Boolean = false): String = withContext(Dispatchers.IO) {
        resolve()
        val body = JSONObject().put("installation_id", installationId).put("device_name", "AI Camera Android")
            .put("new_account", newAccount)
            .toString().toRequestBody("application/json".toMediaType())
        http.newCall(Request.Builder().url("$base/api/device/profile").post(body).build()).execute().use { r ->
            if (!r.isSuccessful) throw ApiHttpException(r.code, detail(r))
            val json = JSONObject(r.body!!.string())
            sid = json.getString("device_token")
            deviceId = json.optString("device_id")
            user = json.getString("name")
            isAdmin = false
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
            isAdmin = false
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


    suspend fun unlockAdmin(code: String): String = withContext(Dispatchers.IO) {
        if (code.length != 4 || !code.all { it.isDigit() }) throw IOException("Enter the 4-digit admin code.")
        resolve()
        val body = JSONObject().put("code", code).put("installation_id", installationId)
            .put("device_name", "AI Camera Android")
            .toString().toRequestBody("application/json".toMediaType())
        http.newCall(Request.Builder().url("$base/api/device/admin-code").post(body).build()).execute().use { r ->
            if (!r.isSuccessful) throw ApiHttpException(r.code, detail(r))
            val json = JSONObject(r.body!!.string())
            sid = json.getString("device_token")
            deviceId = json.optString("device_id")
            user = json.getString("name")
            isAdmin = json.optInt("admin", 0) == 1
            accountCode = ""
            user
        }
    }

    suspend fun promptsFull(): List<PromptItem> = withContext(Dispatchers.IO) {
        val call = { http.newCall(rq("/api/prompts").get().build()).execute() }
        val response = try { call() } catch (e: IOException) { resolve(); call() }
        response.use { r ->
            check(r)
            val json = JSONArray(r.body!!.string())
            (0 until json.length()).map { i ->
                val item = json.getJSONObject(i)
                PromptItem(item.getInt("id"), item.getString("name"), item.getString("text"))
            }
        }
    }

    suspend fun savePrompt(id: Int?, name: String, text: String) = withContext(Dispatchers.IO) {
        val body = JSONObject().put("name", name).put("text", text).toString().toRequestBody("application/json".toMediaType())
        val path = if (id == null) "/api/prompts" else "/api/prompts/$id"
        val request = if (id == null) rq(path).post(body).build() else rq(path).put(body).build()
        http.newCall(request).execute().use { check(it) }
    }

    suspend fun deletePrompt(id: Int) = withContext(Dispatchers.IO) {
        http.newCall(rq("/api/prompts/$id").delete().build()).execute().use { check(it) }
    }

    suspend fun deleteCloudPhoto(name: String) = withContext(Dispatchers.IO) {
        val path = "/api/gallery/" + Uri.encode(name)
        http.newCall(rq(path).delete().build()).execute().use { check(it) }
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

    suspend fun syncGallery(context: Context): Int = withContext(Dispatchers.IO) {
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
        for (file in localFiles) {
            val cloudModified = remote[file.name]
            if (cloudModified == null || file.lastModified() > cloudModified + 1000L) {
                uploadLocalPhoto(file)
                changed++
            }
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
            isAdmin = json.optInt("admin", 0) == 1
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
        isAdmin = false
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
    val modified = file.lastModified()
    val bitmap by produceState<Bitmap?>(initialValue = null, file, sampleSize, modified) {
        value = withContext(Dispatchers.IO) { runCatching { loadBmp(file, sampleSize) }.getOrNull() }
    }
    return bitmap
}

@Composable
private fun CameraPreview(
    capture: ImageCapture,
    frontFacing: Boolean,
    previewAspectRatio: Float?,
    onCameraReady: (Camera?, Float, Float) -> Unit,
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
        val previewModifier = if (previewAspectRatio == null) {
            Modifier.fillMaxSize()
        } else {
            Modifier.fillMaxWidth().aspectRatio(previewAspectRatio).align(Alignment.Center)
        }
        AndroidView(
            modifier = previewModifier,
            factory = { c -> PreviewView(c).also {
                it.scaleType = PreviewView.ScaleType.FILL_CENTER
                previewView = it
            } },
            update = {
                it.scaleType = PreviewView.ScaleType.FILL_CENTER
                previewView = it
            }
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
                val zoomState = camera.cameraInfo.zoomState.value
                val minZoom = zoomState?.minZoomRatio ?: 1f
                val maxZoom = zoomState?.maxZoomRatio ?: 1f
                onCameraReady(camera, minZoom.coerceAtMost(1f), maxZoom.coerceAtLeast(1f))
            }
        } catch (e: Exception) {
            onCameraReady(null, 1f, 1f)
            onError(e.message ?: "Unable to start camera preview")
        }
    }

    DisposableEffect(previewView) {
        onDispose {
            provider?.unbindAll()
            boundCamera = null
            onCameraReady(null, 1f, 1f)
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
    pendingNames: Set<String>,
    revealAnimation: Boolean,
    onBack: () -> Unit,
    onImport: () -> Unit,
    onOpen: (File) -> Unit
) {
    Column(Modifier.fillMaxSize().background(Color(0xFF090A0F)).padding(horizontal = 14.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("‹ Camera", color = Color.White) }
            Spacer(Modifier.weight(1f))
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Gallery", style = MaterialTheme.typography.titleLarge, color = Color.White)
                Text("${files.size} photos", color = Color.Gray, style = MaterialTheme.typography.labelSmall)
            }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onImport) { Text("+ Import", color = Color(0xFFFFB4CB)) }
        }
        if (files.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No photos yet. Capture your first moment.", color = Color.Gray)
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
                    val pending = file.name in pendingNames
                    val blur by animateDpAsState(
                        targetValue = if (pending) 14.dp else 0.dp,
                        animationSpec = if (revealAnimation) tween(700) else androidx.compose.animation.core.snap(),
                        label = "gallery-photo-reveal"
                    )
                    Surface(
                        modifier = Modifier.fillMaxWidth().aspectRatio(1f).clickable { onOpen(file) },
                        shape = RoundedCornerShape(14.dp),
                        color = Color(0xFF17191F)
                    ) {
                        Box {
                            if (bitmap != null) {
                                Image(
                                    bitmap.asImageBitmap(), null,
                                    Modifier.fillMaxSize().blur(blur),
                                    contentScale = ContentScale.Crop
                                )
                            } else {
                                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    CircularProgressIndicator()
                                }
                            }
                            if (pending) {
                                Row(
                                    Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                                        .background(Color(0xAA08090D)).padding(7.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.Center
                                ) {
                                    CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 2.dp)
                                    Spacer(Modifier.width(6.dp))
                                    Text("Enhancing", color = Color.White, style = MaterialTheme.typography.labelSmall)
                                }
                            } else if (revealAnimation && GalleryStorage.isEnhanced(LocalContext.current, file)) {
                                AnimatedVisibility(
                                    visible = true,
                                    enter = slideInHorizontally(initialOffsetX = { it / 3 }) + fadeIn(tween(420)),
                                    modifier = Modifier.align(Alignment.BottomEnd)
                                ) {
                                    Text("ENHANCED", Modifier.padding(6.dp)
                                        .background(Color(0xCC151820), RoundedCornerShape(8.dp)).padding(horizontal = 7.dp, vertical = 4.dp),
                                        color = Color(0xFFFFB5CB), style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
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
    pendingNames: Set<String>,
    onClose: () -> Unit,
    onEdit: (File) -> Unit,
    onDelete: (File) -> Unit,
    onExport: (File) -> Unit,
    onEnhance: (File) -> Unit,
    onRevert: (File) -> Unit
) {
    val context = LocalContext.current
    var index by remember(files, startFile) { mutableIntStateOf(files.indexOf(startFile).coerceAtLeast(0)) }
    val current = files.getOrNull(index) ?: startFile
    val bitmap = rememberPhotoBitmap(current, 1)
    val isPending = current.name in pendingNames
    val isEnhanced = remember(current, current.lastModified()) { GalleryStorage.isEnhanced(context, current) }
    val canRevert = remember(current) { GalleryStorage.hasOriginalBackup(context, current) }
    var scale by remember(current) { mutableFloatStateOf(1f) }
    var offset by remember(current) { mutableStateOf(Offset.Zero) }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(
            Modifier.fillMaxSize().background(Color.Black)
                .pointerInput(index, scale) {
                    var dragTotal = 0f
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            if (scale <= 1.05f && abs(dragTotal) > 64f) {
                                index = if (dragTotal < 0f) (index + 1).coerceAtMost(files.lastIndex)
                                    else (index - 1).coerceAtLeast(0)
                                scale = 1f
                                offset = Offset.Zero
                            }
                            dragTotal = 0f
                        },
                        onHorizontalDrag = { change, amount ->
                            dragTotal += amount
                            change.consume()
                        }
                    )
                }
        ) {
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
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(12.dp)) {
                if (isPending) {
                    Text("Enhancement in progress…", color = Color(0xFFFFB5CB),
                        modifier = Modifier.align(Alignment.CenterHorizontally).padding(bottom = 8.dp))
                }
                Row(
                    Modifier.fillMaxWidth().background(Color(0xCC11131A), RoundedCornerShape(18.dp)).padding(4.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = { onEdit(current); onClose() }) { Text("Edit", color = Color.White) }
                    TextButton(onClick = { onDelete(current); onClose() }) { Text("🗑", color = Color(0xFFFF8F9B)) }
                    TextButton(onClick = { onExport(current) }) { Text("Export", color = Color.White) }
                    if (!isEnhanced && !isPending) {
                        TextButton(onClick = { onEnhance(current); onClose() }) { Text("Enhance", color = Color(0xFFFFB5CB)) }
                    } else if (isEnhanced) {
                        Text("Enhanced", color = Color(0xFFFFB5CB), modifier = Modifier.padding(horizontal = 8.dp))
                    }
                    if (canRevert) TextButton(onClick = { onRevert(current); onClose() }) { Text("Revert", color = Color.White) }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically) {
                    TextButton(enabled = index > 0, onClick = { index--; scale = 1f; offset = Offset.Zero }) {
                        Text("‹ Previous", color = Color.White)
                    }
                    Text("${index + 1} / ${files.size}", color = Color.White)
                    TextButton(enabled = index < files.lastIndex, onClick = { index++; scale = 1f; offset = Offset.Zero }) {
                        Text("Next ›", color = Color.White)
                    }
                }
                Text("Swipe to navigate · pinch or double-tap to zoom", color = Color.LightGray,
                    style = MaterialTheme.typography.labelSmall, modifier = Modifier.align(Alignment.CenterHorizontally))
            }
        }
    }
}


private fun renderEditedBitmap(
    source: Bitmap,
    brightness: Float,
    contrast: Float,
    saturation: Float,
    rotation: Float,
    flipHorizontal: Boolean,
    cropSquare: Boolean
): Bitmap {
    val matrix = android.graphics.Matrix().apply {
        if (rotation != 0f) postRotate(rotation)
        if (flipHorizontal) postScale(-1f, 1f)
    }
    val transformed = if (rotation != 0f || flipHorizontal) {
        Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
    } else source
    val cropped = if (cropSquare) {
        val side = min(transformed.width, transformed.height)
        val left = (transformed.width - side) / 2
        val top = (transformed.height - side) / 2
        Bitmap.createBitmap(transformed, left, top, side, side)
    } else transformed
    val result = Bitmap.createBitmap(cropped.width, cropped.height, Bitmap.Config.ARGB_8888)
    val color = android.graphics.ColorMatrix().apply { setSaturation(saturation) }
    val c = contrast
    val b = brightness * 255f
    val contrastMatrix = android.graphics.ColorMatrix(floatArrayOf(
        c, 0f, 0f, 0f, b,
        0f, c, 0f, 0f, b,
        0f, 0f, c, 0f, b,
        0f, 0f, 0f, 1f, 0f
    ))
    color.postConcat(contrastMatrix)
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        colorFilter = android.graphics.ColorMatrixColorFilter(color)
        isFilterBitmap = true
    }
    android.graphics.Canvas(result).drawBitmap(cropped, 0f, 0f, paint)
    if (cropped !== transformed && cropped !== source) cropped.recycle()
    if (transformed !== source) transformed.recycle()
    return result
}

@Composable
private fun PhotoEditorDialog(
    file: File,
    onCancel: () -> Unit,
    onSave: (File) -> Unit
) {
    val context = LocalContext.current
    val editorScope = rememberCoroutineScope()
    val sourcePreview = rememberPhotoBitmap(file, 3)
    var brightness by remember(file) { mutableFloatStateOf(0f) }
    var contrast by remember(file) { mutableFloatStateOf(1f) }
    var saturation by remember(file) { mutableFloatStateOf(1f) }
    var rotation by remember(file) { mutableFloatStateOf(0f) }
    var flip by remember(file) { mutableStateOf(false) }
    var cropSquare by remember(file) { mutableStateOf(false) }
    var busy by remember(file) { mutableStateOf(false) }
    Dialog(onDismissRequest = { if (!busy) onCancel() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = Color(0xFF0B0D12)) {
            Column(Modifier.fillMaxSize().padding(18.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(enabled = !busy, onClick = onCancel) { Text("Cancel", color = Color.White) }
                    Spacer(Modifier.weight(1f))
                    Text("Edit photo", style = MaterialTheme.typography.titleLarge, color = Color.White)
                    Spacer(Modifier.weight(1f))
                    TextButton(enabled = !busy, onClick = {
                        brightness = 0f; contrast = 1f; saturation = 1f
                        rotation = 0f; flip = false; cropSquare = false
                    }) { Text("Reset", color = Color(0xFFFFB5CB)) }
                }
                Box(Modifier.weight(1f).fillMaxWidth().padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
                    if (sourcePreview != null) {
                        Image(
                            sourcePreview.asImageBitmap(),
                            contentDescription = "Photo edit preview",
                            modifier = Modifier.fillMaxSize().graphicsLayer {
                                rotationZ = rotation
                                scaleX = if (flip) -1f else 1f
                            },
                            contentScale = if (cropSquare) ContentScale.Crop else ContentScale.Fit
                        )
                    } else CircularProgressIndicator()
                }
                Column(Modifier.weight(0.9f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        OutlinedButton(enabled = !busy, onClick = { rotation = (rotation - 90f + 360f) % 360f }) { Text("↶ Rotate") }
                        OutlinedButton(enabled = !busy, onClick = { flip = !flip }) { Text("⇋ Flip") }
                        OutlinedButton(enabled = !busy, onClick = { cropSquare = !cropSquare }) { Text(if (cropSquare) "Free crop" else "Square crop") }
                    }
                    Text("Brightness · ${(brightness * 100).toInt()}%", color = Color.White)
                    Slider(value = brightness, onValueChange = { brightness = it }, valueRange = -0.5f..0.5f, enabled = !busy)
                    Text("Contrast · ${(contrast * 100).toInt()}%", color = Color.White)
                    Slider(value = contrast, onValueChange = { contrast = it }, valueRange = 0.5f..1.8f, enabled = !busy)
                    Text("Saturation · ${(saturation * 100).toInt()}%", color = Color.White)
                    Slider(value = saturation, onValueChange = { saturation = it }, valueRange = 0f..2f, enabled = !busy)
                }
                Button(
                    enabled = !busy && sourcePreview != null,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        busy = true
                        val b = brightness; val c = contrast; val sat = saturation
                        val rot = rotation; val mirrored = flip; val square = cropSquare
                        val output = File(context.cacheDir, "edited_${UUID.randomUUID()}.jpg")
                        // Use the full-resolution original for the saved output.
                        val source = file
                        editorScope.launch(Dispatchers.IO) {
                            try {
                                val full = loadBmp(source, 1)
                                val edited = renderEditedBitmap(full, b, c, sat, rot, mirrored, square)
                                full.recycle()
                                output.outputStream().use { stream ->
                                    if (!edited.compress(Bitmap.CompressFormat.JPEG, 96, stream)) throw IOException("Could not encode edited photo")
                                }
                                edited.recycle()
                                withContext(Dispatchers.Main) { onSave(output); busy = false }
                            } catch (e: Exception) {
                                output.delete()
                                withContext(Dispatchers.Main) {
                                    Toast.makeText(context, e.message ?: "Edit failed", Toast.LENGTH_SHORT).show()
                                    busy = false
                                }
                            }
                        }
                    }
                ) { Text(if (busy) "Saving…" else "Save changes") }
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

private fun cropPhotoForAspect(source: File, mode: String, cacheDir: File): File {
    val ratio = when (mode) {
        "9:16" -> 9f / 16f
        "3:4" -> 3f / 4f
        "1:1" -> 1f
        else -> return source
    }
    val bitmap = loadBmp(source, 1)
    val currentRatio = bitmap.width.toFloat() / bitmap.height.toFloat()
    val cropWidth: Int
    val cropHeight: Int
    if (currentRatio > ratio) {
        cropHeight = bitmap.height
        cropWidth = (cropHeight * ratio).toInt().coerceAtLeast(1)
    } else {
        cropWidth = bitmap.width
        cropHeight = (cropWidth / ratio).toInt().coerceAtLeast(1)
    }
    val left = (bitmap.width - cropWidth) / 2
    val top = (bitmap.height - cropHeight) / 2
    val cropped = Bitmap.createBitmap(bitmap, left, top, cropWidth, cropHeight)
    val out = File(cacheDir, "framed_${UUID.randomUUID()}.jpg")
    try {
        out.outputStream().use { stream ->
            if (!cropped.compress(Bitmap.CompressFormat.JPEG, 96, stream)) throw IOException("Unable to crop image")
        }
    } finally {
        if (cropped !== bitmap) cropped.recycle()
        bitmap.recycle()
    }
    return out
}

@Composable
fun MainScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val capture = remember { ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build() }
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }

    var page by remember { mutableStateOf(MainPage.CAMERA) }
    var prompts by remember { mutableStateOf(listOf<Pair<Int, String>>()) }
    var selectedPrompt by remember { mutableIntStateOf(Api.pid) }
    var cameraFront by remember { mutableStateOf(false) }
    var activeCamera by remember { mutableStateOf<Camera?>(null) }
    var zoomRatio by remember { mutableFloatStateOf(1f) }
    var maxZoomRatio by remember { mutableFloatStateOf(1f) }
    var minZoomRatio by remember { mutableFloatStateOf(1f) }
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
    var accountName by remember { mutableStateOf(Api.user) }
    var accountCode by remember { mutableStateOf(Api.accountCode) }
    var accountBusy by remember { mutableStateOf(false) }
    var accountError by remember { mutableStateOf("") }
    var accountSwitchReady by remember { mutableStateOf(false) }
    var galleryRevealAnimation by remember { mutableStateOf(Api.prefs.getBoolean("gallery_reveal_animation", true)) }
    var aspectMode by remember { mutableStateOf(Api.prefs.getString("photo_aspect", "full") ?: "full") }
    var editingPhoto by remember { mutableStateOf<File?>(null) }
    var adminUnlocked by remember { mutableStateOf(Api.isAdmin) }
    var adminCodeError by remember { mutableStateOf("") }
    var adminPrompts by remember { mutableStateOf(listOf<PromptItem>()) }

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
                        catch (_: Exception) { Api.sid = ""; Api.deviceId = ""; Api.user = ""; Api.isAdmin = false }
                    }
                    if (Api.sid.isBlank() || (Api.accountCode.isBlank() && Api.user.startsWith("guest_"))) {
                        Api.createOrRestoreProfile()
                    }
                    accountName = Api.user
                    accountCode = Api.accountCode
                    adminUnlocked = Api.isAdmin
                    prompts = Api.prompts()
                    if (adminUnlocked) adminPrompts = Api.promptsFull()
                    enqueueGallerySync(context)
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
            enqueueGallerySync(context)
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
        val next = target.coerceIn(minZoomRatio.coerceAtMost(1f), maxZoomRatio.coerceAtLeast(1f))
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
        // Close the preview immediately; the work below happens asynchronously.
        pendingPhoto = null
        savingPhoto = true
        val promptForPhoto = selectedPrompt
        val selectedAspect = aspectMode
        scope.launch {
            var framed: File? = null
            var saved: File? = null
            var succeeded = false
            try {
                saved = withContext(Dispatchers.IO) {
                    framed = cropPhotoForAspect(source, selectedAspect, context.cacheDir)
                    GalleryStorage.save(context, framed ?: source, "original")
                }
                if (enhance) {
                    withContext(Dispatchers.IO) { GalleryStorage.ensureOriginalBackup(context, saved!!) }
                    enqueueEnhancement(context, saved!!, promptForPhoto)
                }
                enqueueGallerySync(context)
                succeeded = true
                Toast.makeText(context, if (enhance) "Saved · enhancing in background" else "Photo saved",
                    Toast.LENGTH_SHORT).show()
                if (page == MainPage.GALLERY) {
                    galleryFiles = withContext(Dispatchers.IO) { GalleryStorage.list(context) }
                }
            } catch (e: Exception) {
                message = e.message ?: "Unable to save photo"
                if (saved != null) withContext(Dispatchers.IO) { GalleryStorage.deletePhoto(context, saved!!) }
                pendingPhoto = source
            } finally {
                if (framed != null && framed != source) framed?.delete()
                if (succeeded) source.delete()
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
        accountBusy = true
        accountError = ""
        scope.launch {
            try {
                Api.disconnect()
                // Assign a fresh USER<N> profile and code immediately after logout.
                Api.createOrRestoreProfile(newAccount = true)
                accountName = Api.user
                accountCode = Api.accountCode
                adminUnlocked = false
                accountSwitchReady = true
                enqueueGallerySync(context)
                message = "New account ready. Enter another account's code or keep this one."
            } catch (e: Exception) {
                accountError = e.message ?: "Could not create a new account"
                accountSwitchReady = false
            } finally { accountBusy = false }
        }
    }

    fun loginAnotherAccount(code: String) {
        if (accountBusy) return
        accountBusy = true
        accountError = ""
        scope.launch {
            try {
                Api.loginWithCode(code)
                adminUnlocked = false
                accountName = Api.user
                accountCode = Api.accountCode
                accountSwitchReady = false
                enqueueGallerySync(context)
                runCatching { Api.syncGallery(context) }
                galleryFiles = withContext(Dispatchers.IO) { GalleryStorage.list(context) }
                message = "Account connected. Cloud gallery synced."
            } catch (e: Exception) { accountError = e.message ?: "Unable to log in with that code" }
            finally { accountBusy = false }
        }
    }

    fun loginAdmin(code: String) {
        if (accountBusy) return
        accountBusy = true
        adminCodeError = ""
        scope.launch {
            try {
                Api.unlockAdmin(code)
                adminUnlocked = true
                accountName = Api.user
                accountCode = ""
                adminPrompts = Api.promptsFull()
                prompts = Api.prompts()
                selectedPrompt = prompts.firstOrNull()?.first ?: selectedPrompt
                message = "Admin account connected"
            } catch (e: Exception) {
                adminCodeError = e.message ?: "Admin login failed"
            } finally { accountBusy = false }
        }
    }

    fun saveAdminPrompt(id: Int?, name: String, text: String) {
        if (!adminUnlocked || accountBusy) return
        accountBusy = true
        adminCodeError = ""
        scope.launch {
            try {
                Api.savePrompt(id, name, text)
                adminPrompts = Api.promptsFull()
                prompts = Api.prompts()
                Toast.makeText(context, "Prompt saved", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                adminCodeError = e.message ?: "Could not save prompt"
            } finally { accountBusy = false }
        }
    }

    fun removeAdminPrompt(id: Int) {
        if (!adminUnlocked || accountBusy) return
        accountBusy = true
        adminCodeError = ""
        scope.launch {
            try {
                Api.deletePrompt(id)
                adminPrompts = Api.promptsFull()
                prompts = Api.prompts()
                Toast.makeText(context, "Prompt deleted", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                adminCodeError = e.message ?: "Could not delete prompt"
            } finally { accountBusy = false }
        }
    }

    when (page) {
        MainPage.CAMERA -> {
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                if (granted) {
                    CameraPreview(
                        capture = capture,
                        frontFacing = cameraFront,
                        previewAspectRatio = when (aspectMode) {
                            "9:16" -> 9f / 16f
                            "3:4" -> 3f / 4f
                            "1:1" -> 1f
                            else -> null
                        },
                        onCameraReady = { camera, min, max ->
                            activeCamera = camera
                            minZoomRatio = min
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

                Row(
                    Modifier.fillMaxWidth().align(Alignment.TopCenter).padding(horizontal = 18.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val hasFlash = activeCamera?.cameraInfo?.hasFlashUnit() == true
                    Surface(color = Color(0x66000000), shape = RoundedCornerShape(24.dp)) {
                        TextButton(enabled = hasFlash, onClick = {
                            flashEnabled = !flashEnabled
                            activeCamera?.cameraControl?.enableTorch(flashEnabled)
                        }) { Text(if (flashEnabled) "⚡ ON" else "⚡", color = Color.White) }
                    }
                    Spacer(Modifier.weight(1f))
                    Surface(color = Color(0x99000000), shape = RoundedCornerShape(24.dp)) {
                        TextButton(onClick = { settingsOpen = true }) {
                            Text("⚙", color = Color.White, style = MaterialTheme.typography.titleLarge)
                        }
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

                // Compact zoom selector positioned above the shutter row, like the supplied camera reference.
                Row(
                    Modifier.align(Alignment.BottomCenter).padding(bottom = 154.dp)
                        .background(Color(0x99000000), RoundedCornerShape(28.dp)).padding(horizontal = 5.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    listOf(0.5f, 1f, 2f).forEach { factor ->
                        val available = factor >= minZoomRatio - 0.01f && factor <= maxZoomRatio + 0.01f
                        TextButton(
                            enabled = available,
                            onClick = { setZoom(factor) },
                            colors = ButtonDefaults.textButtonColors(
                                contentColor = Color.White,
                                disabledContentColor = Color.Gray
                            ),
                            contentPadding = PaddingValues(horizontal = 13.dp, vertical = 2.dp)
                        ) {
                            Text(if (zoomRatio == factor) "${factor}×" else factor.toString().removeSuffix(".0"))
                        }
                    }
                }

                Column(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                        .background(Color(0xE6090A0F))
                        .navigationBarsPadding()
                        .padding(top = 10.dp, bottom = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Row(
                        Modifier.fillMaxWidth().height(88.dp).padding(horizontal = 24.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        TextButton(onClick = { page = MainPage.GALLERY }) {
                            Text("▣", color = Color.White, style = MaterialTheme.typography.headlineMedium)
                        }
                        Box(
                            Modifier.size(76.dp).border(4.dp, Color.White, CircleShape).padding(6.dp)
                                .background(if (captureRunning) Color.LightGray else Color.White, CircleShape)
                                .clickable(enabled = granted && !captureRunning) { shoot() }
                        )
                        TextButton(onClick = {
                            cameraFront = !cameraFront
                            zoomRatio = 1f
                            flashEnabled = false
                        }) {
                            Text("⟳", color = Color.White, style = MaterialTheme.typography.headlineLarge)
                        }
                    }
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        listOf("9:16", "3:4", "1:1", "Full").forEach { ratio ->
                            FilterChip(
                                selected = aspectMode == ratio.lowercase().let { if (it == "full") "full" else ratio },
                                onClick = {
                                    aspectMode = if (ratio == "Full") "full" else ratio
                                    Api.prefs.edit().putString("photo_aspect", aspectMode).apply()
                                },
                                label = { Text(ratio) }
                            )
                        }
                    }
                    if (prompts.isNotEmpty()) {
                        Chips(prompts, selectedPrompt) { selectedPrompt = it; Api.pid = it }
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
            val pendingNames = workInfos.filter {
                it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.BLOCKED || it.state == WorkInfo.State.RUNNING
            }.mapNotNull { loadEnhancementTaskMetadata(context, it.id)?.sourceFile?.let { p -> File(p).name } }.toSet()
            GalleryScreen(
                files = galleryFiles,
                pendingNames = pendingNames,
                revealAnimation = galleryRevealAnimation,
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
                    pendingNames = pendingNames,
                    onClose = { viewerFiles = null; viewerStart = null },
                    onEdit = { editingPhoto = it },
                    onDelete = { file ->
                        scope.launch {
                            try {
                                runCatching { Api.deleteCloudPhoto(file.name) }
                                withContext(Dispatchers.IO) { GalleryStorage.deletePhoto(context, file) }
                                galleryFiles = withContext(Dispatchers.IO) { GalleryStorage.list(context) }
                                enqueueGallerySync(context)
                                Toast.makeText(context, "Photo deleted", Toast.LENGTH_SHORT).show()
                            } catch (e: Exception) { message = e.message ?: "Unable to delete photo" }
                        }
                    },
                    onExport = { file ->
                        scope.launch(Dispatchers.IO) {
                            try {
                                GalleryStorage.exportToDownloads(context, file)
                                withContext(Dispatchers.Main) {
                                    Toast.makeText(context, "Exported to Downloads/AI Camera", Toast.LENGTH_SHORT).show()
                                }
                            } catch (e: Exception) {
                                withContext(Dispatchers.Main) { Toast.makeText(context, e.message ?: "Export failed", Toast.LENGTH_SHORT).show() }
                            }
                        }
                    },
                    onEnhance = { file ->
                        scope.launch {
                            try {
                                GalleryStorage.ensureOriginalBackup(context, file)
                                enqueueEnhancement(context, file, selectedPrompt)
                                Toast.makeText(context, "Enhancement started in background", Toast.LENGTH_SHORT).show()
                            } catch (e: Exception) { message = e.message ?: "Unable to queue enhancement" }
                        }
                    },
                    onRevert = { file ->
                        scope.launch {
                            try {
                                withContext(Dispatchers.IO) { GalleryStorage.revertOriginal(context, file) }
                                enqueueGallerySync(context)
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
            galleryRevealAnimation = galleryRevealAnimation,
            onRevealAnimationChange = {
                galleryRevealAnimation = it
                Api.prefs.edit().putBoolean("gallery_reveal_animation", it).apply()
            },
            adminUnlocked = adminUnlocked,
            adminCodeError = adminCodeError,
            adminPrompts = adminPrompts,
            onAdminLogin = { loginAdmin(it) },
            onSavePrompt = { id, name, text -> saveAdminPrompt(id, name, text) },
            onDeletePrompt = { removeAdminPrompt(it) },
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
    timerSeconds: Int,
    gridMode: Int,
    onTimerChange: (Int) -> Unit,
    onGridChange: (Int) -> Unit,
    galleryRevealAnimation: Boolean,
    onRevealAnimationChange: (Boolean) -> Unit,
    adminUnlocked: Boolean,
    adminCodeError: String,
    adminPrompts: List<PromptItem>,
    onAdminLogin: (String) -> Unit,
    onSavePrompt: (Int?, String, String) -> Unit,
    onDeletePrompt: (Int) -> Unit,
    onClose: () -> Unit
) {
    var showCodeDialog by remember { mutableStateOf(false) }
    var showLogoutWarning by remember { mutableStateOf(false) }
    var showLoginDialog by remember { mutableStateOf(false) }
    var savedCodeConfirmed by remember { mutableStateOf(false) }
    var enteredCode by remember { mutableStateOf("") }
    var adminCodeInput by remember { mutableStateOf("0007") }
    var promptEditor by remember { mutableStateOf(false) }
    var editingPrompt by remember { mutableStateOf<PromptItem?>(null) }
    var promptName by remember { mutableStateOf("") }
    var promptText by remember { mutableStateOf("") }
    var promptError by remember { mutableStateOf("") }
    var deletePromptId by remember { mutableIntStateOf(0) }

    LaunchedEffect(switchReady) { if (switchReady) showLoginDialog = true }
    LaunchedEffect(accountName) {
        if (accountName.isNotBlank()) { showLoginDialog = false; enteredCode = "" }
    }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = Color(0xFF0B0D12)) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onClose) { Text("‹ Camera", color = Color.White) }
                    Spacer(Modifier.weight(1f))
                    Text("Settings", color = Color.White, style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onClose) { Text("Done", color = Color(0xFFFFB5CB)) }
                }
                Divider(color = Color(0xFF272B34))
                Column(
                    Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    SettingsCard(title = "My Account", subtitle = accountName.ifBlank { "Waiting for your server connection" }) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(enabled = !accountBusy && accountName.isNotBlank(), onClick = {
                                showCodeDialog = true
                                onShowCode()
                            }) { Text("Show Code") }
                            Button(enabled = !accountBusy && accountName.isNotBlank(), onClick = {
                                savedCodeConfirmed = false
                                showLogoutWarning = true
                            }) { Text("Login Another Account") }
                        }
                        if (accountError.isNotBlank()) {
                            Text(accountError, color = Color(0xFFFFAAB6), style = MaterialTheme.typography.bodySmall)
                        }
                        Text("Your 4-digit account code opens this account's backed-up photos on another device.",
                            color = Color.Gray, style = MaterialTheme.typography.bodySmall)
                    }

                    SettingsCard(title = "Server status", subtitle = serverStatus) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val statusColor = when (serverOnline) {
                                true -> Color(0xFF69D69A)
                                false -> Color(0xFFFF8797)
                                null -> Color.LightGray
                            }
                            Box(Modifier.size(10.dp).background(statusColor, CircleShape))
                            Spacer(Modifier.width(9.dp))
                            Text(serverStatus, color = statusColor)
                            Spacer(Modifier.weight(1f))
                            TextButton(enabled = !checkingServer, onClick = onRefreshServer) {
                                Text(if (checkingServer) "Checking…" else "Refresh", color = Color(0xFFFFB5CB))
                            }
                        }
                    }

                    SettingsCard(title = "Camera", subtitle = "Capture preferences") {
                        Text("Timer", color = Color.White)
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            listOf(0, 3, 5, 10).forEach { seconds ->
                                FilterChip(
                                    selected = timerSeconds == seconds,
                                    onClick = { onTimerChange(seconds) },
                                    label = { Text(if (seconds == 0) "Off" else "${seconds}s") }
                                )
                            }
                        }
                        Text("Composition grid", color = Color.White)
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
                            listOf(0, 3, 4, 5).forEach { mode ->
                                val label = when (mode) { 0 -> "Off"; 3 -> "3×3"; 4 -> "4×4"; else -> "Golden" }
                                FilterChip(selected = gridMode == mode, onClick = { onGridChange(mode) }, label = { Text(label) })
                            }
                        }
                        Text("Tap the preview to focus. Flash is available on supported cameras.",
                            color = Color.Gray, style = MaterialTheme.typography.bodySmall)
                    }

                    SettingsCard(title = "Photos", subtitle = "Gallery and enhancement appearance") {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Enhanced reveal animation", color = Color.White)
                                Text("Slide the enhanced image into view when processing finishes.",
                                    color = Color.Gray, style = MaterialTheme.typography.bodySmall)
                            }
                            Switch(checked = galleryRevealAnimation, onCheckedChange = onRevealAnimationChange)
                        }
                    }

                    SettingsCard(title = "About Image", subtitle = "How AI Camera handles your photos") {
                        Text("Save", color = Color(0xFFFFB5CB), style = MaterialTheme.typography.titleSmall)
                        Text("Photos are saved locally first. Uploads are queued when the server or internet is unavailable.",
                            color = Color.LightGray, style = MaterialTheme.typography.bodySmall)
                        Text("Save + Enhance", color = Color(0xFFFFB5CB), style = MaterialTheme.typography.titleSmall)
                        Text("The original is kept privately for recovery. The gallery shows one photo entry and replaces it with the enhanced result when ready.",
                            color = Color.LightGray, style = MaterialTheme.typography.bodySmall)
                        Text("Privacy", color = Color(0xFFFFB5CB), style = MaterialTheme.typography.titleSmall)
                        Text("Photos are separated by account. Keep your 4-digit account code private.",
                            color = Color.LightGray, style = MaterialTheme.typography.bodySmall)
                    }

                    SettingsCard(title = "Admin tools", subtitle = if (adminUnlocked) "Administrator access enabled" else "Restricted prompt management") {
                        if (!adminUnlocked) {
                            OutlinedTextField(
                                value = adminCodeInput,
                                onValueChange = { adminCodeInput = it.filter(Char::isDigit).take(4) },
                                label = { Text("Admin code") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Button(
                                enabled = adminCodeInput.length == 4 && !accountBusy,
                                onClick = { onAdminLogin(adminCodeInput) },
                                modifier = Modifier.fillMaxWidth()
                            ) { Text(if (accountBusy) "Checking…" else "Unlock Admin") }
                            if (adminCodeError.isNotBlank()) Text(adminCodeError, color = Color(0xFFFFAAB6))
                            Text("Default admin code: 0007", color = Color.Gray, style = MaterialTheme.typography.bodySmall)
                        } else {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Enhancement prompts", color = Color.White, style = MaterialTheme.typography.titleSmall)
                                Spacer(Modifier.weight(1f))
                                Button(onClick = {
                                    editingPrompt = null
                                    promptName = ""
                                    promptText = ""
                                    promptError = ""
                                    promptEditor = true
                                }) { Text("+ Add") }
                            }
                            if (adminPrompts.isEmpty()) {
                                Text("No prompts are configured.", color = Color.Gray)
                            } else {
                                adminPrompts.forEach { item ->
                                    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF20242D))) {
                                        Column(Modifier.fillMaxWidth().padding(10.dp)) {
                                            Text(item.name, color = Color.White, style = MaterialTheme.typography.titleSmall)
                                            Text(item.text.take(160), color = Color.LightGray, style = MaterialTheme.typography.bodySmall)
                                            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                                                TextButton(onClick = {
                                                    editingPrompt = item
                                                    promptName = item.name
                                                    promptText = item.text
                                                    promptError = ""
                                                    promptEditor = true
                                                }) { Text("Edit") }
                                                TextButton(onClick = { deletePromptId = item.id }) {
                                                    Text("Delete", color = Color(0xFFFF8797))
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                            if (adminCodeError.isNotBlank()) Text(adminCodeError, color = Color(0xFFFFAAB6))
                        }
                    }
                }
            }
        }
    }

    if (showCodeDialog) {
        AlertDialog(
            onDismissRequest = { showCodeDialog = false },
            title = { Text("Your account code") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Save this code. It is the only information needed to log in to this account on another device.")
                    Text(if (accountCode.length == 4) accountCode else if (accountBusy) "Loading…" else "Code unavailable",
                        style = MaterialTheme.typography.headlineLarge, color = Color(0xFFFFB5CE))
                    Text("Anyone with this code can access your account gallery. Keep it private.",
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
                    Text("A new account code will be assigned after logout. Your old account and its cloud photos will remain saved.")
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
                    Text("Enter the other account's 4-digit code. If you cancel, the newly assigned account stays active.")
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

    if (promptEditor) {
        AlertDialog(
            onDismissRequest = { promptEditor = false },
            title = { Text(if (editingPrompt == null) "Add prompt" else "Edit prompt") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = promptName, onValueChange = { promptName = it }, label = { Text("Prompt name") })
                    OutlinedTextField(value = promptText, onValueChange = { promptText = it }, label = { Text("Prompt text") },
                        modifier = Modifier.fillMaxWidth().height(190.dp))
                    if (promptError.isNotBlank()) Text(promptError, color = Color(0xFFFFAAB6))
                }
            },
            confirmButton = {
                TextButton(enabled = promptName.isNotBlank() && promptText.isNotBlank() && !accountBusy, onClick = {
                    onSavePrompt(editingPrompt?.id, promptName, promptText)
                    promptEditor = false
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { promptEditor = false }) { Text("Cancel") } }
        )
    }

    if (deletePromptId != 0) {
        AlertDialog(
            onDismissRequest = { deletePromptId = 0 },
            title = { Text("Delete prompt?") },
            text = { Text("This removes the prompt from the camera's prompt list.") },
            confirmButton = {
                TextButton(onClick = { onDeletePrompt(deletePromptId); deletePromptId = 0 }) {
                    Text("Delete", color = Color(0xFFFF8797))
                }
            },
            dismissButton = { TextButton(onClick = { deletePromptId = 0 }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun SettingsCard(title: String, subtitle: String, content: @Composable ColumnScope.() -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF171B23)), shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.fillMaxWidth().padding(15.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Text(title, color = Color.White, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, color = Color.Gray, style = MaterialTheme.typography.bodySmall)
            content()
        }

    editingPhoto?.let { file ->
        PhotoEditorDialog(
            file = file,
            onCancel = { editingPhoto = null },
            onSave = { output ->
                scope.launch {
                    try {
                        withContext(Dispatchers.IO) {
                            GalleryStorage.ensureOriginalBackup(context, file)
                            GalleryStorage.replacePrimary(context, file, output)
                        }
                        enqueueGallerySync(context)
                        galleryFiles = withContext(Dispatchers.IO) { GalleryStorage.list(context) }
                        editingPhoto = null
                        output.delete()
                        Toast.makeText(context, "Edits saved", Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        message = e.message ?: "Could not save edits"
                    }
                }
            }
        )
    }
    }
}

private fun Float.formatZoom(): String = if (this % 1f == 0f) this.toInt().toString() else String.format("%.1f", this)