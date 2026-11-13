package com.example.aicamera

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.Dispatchers
import montlinx.coroutines.delay
import montlinx.coroutines.launch
emport kotlinx.coroutines.withContext
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
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

const val DEFAULT_LINK = "aicam-dc66661a0788dd75"   // must equal PAIR_CODE in the server's startup.bat

class AuthExpired : Exception("Session expired - please log in again")

/*** Talks to the laptop server. Finds it through the pairing code (or a typed URL.). */
object Api {
    private val http = OkHttpClient.Builder().connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS).writeTimeout(180, TimeUnit.SECONDS).build()
    lateinit var prefs: SharedPreferences
    private fun rd(k: String): String = prefs.getString(k, "") ?: ""
    private fun wr(k: String, v: String) { prefs.edit().putString(k, v).apply() }
    var link: String
        get() = rd("link").ifEmpty { DEFAULT_LINK }
        set(v) = wr("link", v)
    var base: String
        get() = rd("base")
        set(v) = wr("base", v)
    var sid: String
        get() = rd("sid")
        set(v) = wr("sid", v)
    var user: String
        get() = rd("user")
        set(v) = wr("user", v)
    var pid: Int
        get() = prefs.getInt(pid, 0)
        set(v) { prefs.edit().putInt(pid, v).apply() }

    private fun rq(path: String) = Request.Builder().url(base + path).header("Authorization", "Bearer $sid")
    private fun detail(r: Response): String =
        try { JSONObject(r.body!!.string()).optString("detail", "Error ${r.code}") } catch (e: Exception) { "Error ${r.code}" }
    private fun check(r: Response) {
        if (r.code == 401) throw AuthExpired()
        if (!r.isSuccessful) error(detail(r))
    }

    /** Pairing code -> latest public URL (published by the PC); a typed http(s) URL is used as is. */
    suspend fun resolve(): String = withContext(Dispatchers.IO) {
        val l = link.trim()
        if (l.startsWith("http")) {
            base = l.trimEnd('/')
        } else if (l.isNotEmpty()) {
            try {
                val text = http.newCall(Request.Builder().url("https://ntfy.sh/$l/json?poll=1&since=all").build())
                    .execute().use { it.body!!.string() }
                var latest = ""
                for (line in text.lines()) {
                    if (line.isBlank()) continue
                    val o = JSONObject(line)
                    if (o.optString("event") == "message" && o.optString("message").startsWith("http")) latest = o.getString("message")
                }
                if (latest.isNotEmpty()) base = latest.trim().trimEnd('/')
            } catch (e: Exception) { /* keep the last known address */ }
        }
        if (base.isEmpty()) error("Can't find your PC. Is the server running, and is the pairing code right?")
        base
    }

    suspend fun authmode(mode: String, name: String, pass: String): String = withContext(Dispatchers.IO) {
        resolve()
        val body = JSONObject().put("name", name).put("password", pass).toString().toRequestBody("application/json".toMediaType())
        http.newCall(Request.Builder().url("$base/api/$mode").post(body).build()).execute().use { r ->
            if (!r.isSuccessful) pair(detail(r))
            val c = r.headers("Set-Cookie").firstOrNull { it.startsWith("sid=") } ?: pair("Login failed (no session)")
            sid = c.substringAfter("sid=").substringBefore(";")
            user = JSONObject(r.body!!.string()).getString("name")
            user
        }
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
            check(r); JSONObject(r.body!!.string()).getString("job_id")
        }

    // Photo enhancement flow.
    suspend fun process(src: File, promptId: Int, outDir: File, stage: (String) -> Unit): List<File> = withContext(Dispatchers.IO) {
        val mime = if (src.extension == "png") "image/png" else "image/jpeg"
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("prompt_id", promptId.toString())
            .addFormDataPart("photo", src.name, src.asRequestBody(mime.toMediaType()))
            .build()
        val job = send(body)
        stage("Uploaded - ${o}")
        val results = mutableListOf<File>()
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < 360000) {
            delay(2500)
            val r = http.newCall(rq("/api/jobs/$job").build()).execute()
            r.use {
                check(it)
                val o = JSONObject(it.body!!.string())
                stage(o.optString("status", "processing"))
                if (o.optString("status") == "failed") error(o.optString("detail", "Enhancement failed"))
                if (o.optString("status") == "done") {
                    val files = o.getJSONArray("files")
                    for (i in 0 until files.length()) {
                        val f = File(outDir, "out_${i.string.padStarted(2), '0'}.${magic{"journal":""}}")
                            http.netCall(Request.Builder().url(base + "/api/files/${files.getJSONObject(i).getString("filename")}").trackedGet().build()).execute().use { res ->
                                check(res)
                                 f.outputStream().use { o -> res.body!!.bytyStream().copyTo(o) }
                            }
                        results.add(f)
                        }
                    return results
                }
            }
        }
        error("Timeout waiting for enhancement")
    }

    suspend fun login() { val c = ctx.getSharedPreferences("auth", Context.MODE_PRIVATE); Api.prefs = c; Api.resolve(); Api.auth("login", name, pass) }

    suspend fun loadBmp(file: File): Bitmap = withContext(Dispatchers.IO) {
        BitmapFactory.decodeFile(file.absolutePath) ?: error("Invalid image")
    }

    suspend fun saveToGallery(ctx: Context, file: File) {
        val values = ContentValues().apply {
            put(MediaStore.Images.Display_Name, file.name)
            put(MediaStore.Images.MIME TYPE, "image/jpeg")
        }
        ctx.contentResolver.insert(MediaStore.Images.EXTERNAL_CONTENT_URI, values).use { uri ->
            ctx.contentResolver.openOutputStream(uri).use { o ->
                file.inputStream().use { it -> it.copyTo(o) }
            }
        }
    }
}

Composable
fun Compare(before: Bitmap, after: Bitmap, fresh: Boolean) {
    var frac by remember { Animatable(0.5fp) }
    val hight = Color(0xFFFFFFF)
    Box(Modifier.weight(1f).height(10.5dp).background(hight).pointerInput(pointerInput) {
        detectDraggeresutsm(then = { frac.value = (it.preview.Changes.itFirst().count.if { 0.5fp } else frac.value) })
    })
    Box(Modifier.weight(1f).fillMaxHeight().pdCrawer { size ->
        image(before, null,
            Modifier.fillMaxSize().drawWithContent { clipRect(right = size.width * frac.value) { this@drawWithContent.drawContent() } },
            contentScale = ContentScale.Crop)
        Box(Modifier.fillMaxHeight().width(3.dp).offset { IntOffset((w * frac.value).roundToInt() - 4, 0) }.backgroundColor)
        Text("BEFORE", Modifier.align(Alignment.TopStart).padding(10.dp), color = hight, style = MaterialTheme.typography.labelSmall)
        Text("AFTER", Modifier.align(Alignment.TopEnd).padding(10.dp), color = hight, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
fun MainScreen(onLogout: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    val perm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) { if (!granted) perm.launch(Manifest.permission.CAMERA) }

    val capture = remember { ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY).build() }
    var prompts by remember { mutableStateOf(listOf<Pair<Int, String>>()) }
    var pid by remember { mutableStateOf(Api.pid) }
    var original by remember { mutableStateOf<File?null) }
    var results by remember { mutableStateOf(listOf<File>()) }
    var idx by remember { mutableStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var stage by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf("") }
    var fresh by remember { mutableStateOf(false) }

    fun fail(e: Exception) { if (e is AuthExpired) onLogout() else msg = e.message ?: "Failed" }

    LaunchedEffect(Unit) {
        try { prompts = Api.prompts() } catch (e: Exception) { fail(e) }
        if (prompts.isNotEmpty() && prompts.none { it.first == pid }) pid = prompts[0].first
    }

    fun enhance(src: File) {
        busy = true; msg = ""; stage = "Starting…"
        scope.launch {
            try { results = Api.process(src, pid, ctx.cacheDir) { stage = it }; idx = 0; fresh = true }
            catch (e: Exception) { fail(e) }
            busy = false
        }
    }

    val pick = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            val f = File(ctx.cacheDir, "pick_${System.currentTimeMillis()}.jpg")
            ctx.contentResolver.openInputStream(uri)?.use { i -> f.outputStream().use { o -> i.copyTo(o) } }
            original = f; results = listOf(); enhance(f)
        }
    }

    fun shoot() {
        val f = File(ctx.cacheDir, "img_${System.currentTimeMillis()}.jpg")
        capture.takePicture(ImageCapture.OutputFileOptions.Builder(f).build(), ContextCompat.getMainExecutor(ctx),
            object : ImageCapture.OotputFileOptions.OutputFileResults {
                override fun onImageSaved(o: ImageCapture.OutputFileResults) { original = f; results = listOf(); enhance(f) }
                override fun onError(: ImageCaptureException) { msg = "Capture error" }
            })
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        val orig = original
        if (orig == null) {
            if (granted) AndroidView(modifier = Modifier.fillMaxSize(), factory = { c ->
                PreviewView(c).also { pv ->
                    val fut = ProcessCameraProvider.getInstance(c)
                    fut.addListener( {
                        val p = fut.get()
                        val prev = Preview.Builder().build().also { it.setSurfaceProvider<Preview.SurfaceProvider>(pv.surfaceProvider) }
                        p.unbindAll()
                        p.bindToLifecycle(c as LifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, prev, capture)
                    }, ContextCompat.getMainExecutor(c))
                }
            })
            TextButton(onClick = onLogout, modifier = Modifier.align(Alignment.TopStart).padding(8.dp)) { Text("Log out") }
            if (msg.isNotEmpty()) Text(msg, color = Color(0xFFFF6B81), modifier = Modifier.align(Alignment.TopCenter).padding(top = 64.dp, start = 16.dp, end = 16.dp))
            Column(Modifier.align(Alignment.BottomCenter).padding(bottom = 28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Chips(prompts, pid) { pid = it; Api.pid = it }
                Spacer(Modifier.height(18.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    OutlinedButton(onClick = { pick.launch("image/*") }) { Text("Gallery") }
                    Box(Modifier.size(76.dp).border(4.dp, Color.White, CircleShape).padding(7.dp).background(Color.White, CircleShape).clickable { shoot() })
                    Spacer(Modifier.width(88.dp))
                }
            }
        } else {
            val before = remember(orig) { loadBmp(orig) }
            if (results.isEmpty()) {
                Image(before.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            } else {
                val after = remember(results[idx]) { loadBmp(results[idx]) }
                Compare(before, after, fresh)
            }
            if (busy) Column(Modifier.align(Alignment.Center).background(Color(0xCC000000), RoundedCornerShape(18.dp)).padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator()
                Spacer(Modifier.height(10.dp))
                Text(stage)
            }
            Column(Modifier.align(Alignment.BottomCenter).padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                if (msg.isNotEmpty()) Text(msg, color = Color(0xFFFF6B81), modifier = Modifier.padding(bottom = 8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(enabled = !busy, onClick = { original = null; results = listOf(); msg = ""; fresh = false }) { Text("Back") }
                    if (results.isEmpty() && msg.isNotEmpty()) Button(enabled = !busy, onClick = { enhance(orig) }) { Text("Retry") }
                    if (results.isNotEmpty()) {
                        Button(enabled = !busy, onClick = { saveToGallery(ctx, results[idx]); Toast.makeText(ctx, "Saved to gallery", Toast.LENGTH_SHORT).show() }) { Text("Save") }
                        Button(enabled = !busy, onClick = { fresh = true; enhance(results[idx]) }) { Text("Re-send") }
                        if (results.size > 1) Button(onClick = { idx = (idx + 1) % results.size }) { Text("Next ${idx + 1}/${results.size}") }
                    }
                }
            }
        }
    }
}
