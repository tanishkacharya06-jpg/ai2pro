package com.example.aicamera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ColorMatrix as AndroidColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Canvas as AndroidCanvas
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.Image
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope

@Composable
fun PhotoEditorScreen(
    file: File,
    onBack: () -> Unit,
    onSave: suspend (File) -> Unit
) {
    var bitmap by remember(file) { mutableStateOf<Bitmap?>(null) }
    var rotation by remember(file) { mutableIntStateOf(0) }
    var mirrored by remember(file) { mutableStateOf(false) }
    var brightness by remember(file) { mutableFloatStateOf(0f) }
    var contrast by remember(file) { mutableFloatStateOf(1f) }
    var saturation by remember(file) { mutableFloatStateOf(1f) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    LaunchedEffect(file) {
        bitmap = withContext(Dispatchers.IO) { runCatching { loadBmp(file, 2) }.getOrNull() }
        if (bitmap == null) error = "Unable to open this image"
    }

    val composeMatrix = remember(brightness, contrast, saturation) {
        ColorFilter.colorMatrix(ColorMatrix(photoEditColorValues(brightness, contrast, saturation)))
    }

    Column(Modifier.fillMaxSize().background(Color(0xFF0C0D12)).padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("‹", color = Color.White, style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(end = 14.dp))
            Text("Edit photo", color = Color.White, style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.weight(1f))
            Text(file.name, color = Color.Gray, style = MaterialTheme.typography.labelSmall)
        }
        Spacer(Modifier.height(14.dp))
        Card(Modifier.weight(1f).fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
            if (bitmap != null) {
                Image(
                    bitmap!!.asImageBitmap(),
                    contentDescription = "Photo editing preview",
                    modifier = Modifier.fillMaxSize().graphicsLayer {
                        rotationZ = rotation.toFloat()
                        scaleX = if (mirrored) -1f else 1f
                    },
                    contentScale = ContentScale.Fit,
                    colorFilter = composeMatrix
                )
            } else {
                Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center) {
                    CircularProgressIndicator()
                    Text(error.ifBlank { "Loading photo…" }, color = Color.Gray)
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(enabled = !saving, modifier = Modifier.weight(1f), onClick = { rotation = (rotation + 90) % 360 }) {
                Text("Rotate")
            }
            OutlinedButton(enabled = !saving, modifier = Modifier.weight(1f), onClick = { mirrored = !mirrored }) {
                Text(if (mirrored) "Unflip" else "Flip")
            }
            OutlinedButton(enabled = !saving, modifier = Modifier.weight(1f), onClick = {
                rotation = 0; mirrored = false; brightness = 0f; contrast = 1f; saturation = 1f
            }) { Text("Reset") }
        }
        Text("Brightness", color = Color.White)
        Slider(value = brightness, onValueChange = { brightness = it }, valueRange = -0.35f..0.35f, enabled = !saving)
        Text("Contrast", color = Color.White)
        Slider(value = contrast, onValueChange = { contrast = it }, valueRange = 0.6f..1.6f, enabled = !saving)
        Text("Saturation", color = Color.White)
        Slider(value = saturation, onValueChange = { saturation = it }, valueRange = 0f..2f, enabled = !saving)
        if (error.isNotBlank()) Text(error, color = Color(0xFFFF8797))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(enabled = !saving, modifier = Modifier.weight(1f), onClick = onBack) { Text("Cancel") }
            Button(enabled = bitmap != null && !saving, modifier = Modifier.weight(1f), onClick = {
                saving = true
                error = ""
                // The full-resolution render runs on the IO dispatcher; the editor stays responsive.
                scope.launch {
                    try {
                        val edited = withContext(Dispatchers.IO) {
                            renderEditedPhoto(file, context.cacheDir, rotation, mirrored, brightness, contrast, saturation)
                        }
                        onSave(edited)
                    } catch (e: Exception) {
                        error = e.message ?: "Could not save edits"
                        saving = false
                    }
                }
            }) { Text(if (saving) "Saving…" else "Save changes") }
        }
    }
}

private fun renderEditedPhoto(
    source: File,
    outDir: File,
    rotation: Int,
    mirrored: Boolean,
    brightness: Float,
    contrast: Float,
    saturation: Float
): File {
    val input = loadBmp(source, 1)
    val transform = Matrix().apply {
        postRotate(rotation.toFloat())
        if (mirrored) postScale(-1f, 1f, input.width / 2f, input.height / 2f)
    }
    val rotated = Bitmap.createBitmap(input, 0, 0, input.width, input.height, transform, true)
    if (rotated !== input) input.recycle()
    val output = Bitmap.createBitmap(rotated.width, rotated.height, Bitmap.Config.ARGB_8888)
    val matrix = AndroidColorMatrix(photoEditColorValues(brightness, contrast, saturation))
    val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        colorFilter = ColorMatrixColorFilter(matrix)
    }
    AndroidCanvas(output).drawBitmap(rotated, 0f, 0f, paint)
    rotated.recycle()
    val target = File(outDir, "edited_${UUID.randomUUID()}.jpg")
    FileOutputStream(target).use { stream ->
        if (!output.compress(Bitmap.CompressFormat.JPEG, 96, stream)) throw IOException("Unable to encode edited photo")
        stream.flush()
        stream.fd.sync()
    }
    output.recycle()
    return target
}

private fun photoEditColorValues(brightness: Float, contrast: Float, saturation: Float): FloatArray {
    val inv = 1f - saturation
    val r = 0.213f * inv
    val g = 0.715f * inv
    val b = 0.072f * inv
    val c = contrast
    val shift = brightness * 255f
    return floatArrayOf(
        (r + saturation) * c, g * c, b * c, 0f, shift,
        r * c, (g + saturation) * c, b * c, 0f, shift,
        r * c, g * c, (b + saturation) * c, 0f, shift,
        0f, 0f, 0f, 1f, 0f
    )
}
