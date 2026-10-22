package com.openthinking.blankassistant.editor

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import com.dot.gallery.R
import com.dot.gallery.ui.theme.GalleryTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.ceil
import kotlin.math.max

/**
 * Markup + crop editor for a screenshot. Input: [android.content.Intent.getData] = a MediaStore image
 * Uri owned by this app. Output: the edited PNG is written back over the same Uri.
 *
 * UI is ReFra's (Apache-2.0) markup components and cropper; this class is only the host.
 */
class EditorActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val uri = intent?.data
        if (uri == null) {
            finish()
            return
        }

        setContent {
            GalleryTheme(darkTheme = true) {
                var source by remember { mutableStateOf<Bitmap?>(null) }
                var saving by remember { mutableStateOf(false) }

                LaunchedEffect(uri) {
                    val bitmap = withContext(Dispatchers.IO) { decode(uri) }
                    if (bitmap == null) {
                        Toast.makeText(this@EditorActivity, R.string.editor_load_failed, Toast.LENGTH_SHORT).show()
                        finish()
                    } else {
                        source = bitmap
                    }
                }

                EditorScreen(
                    source = source,
                    saving = saving,
                    onCancel = { finish() },
                    onSave = { edited ->
                        saving = true
                        lifecycleScope.launch {
                            val ok = withContext(Dispatchers.IO) { write(uri, edited) }
                            Toast.makeText(
                                this@EditorActivity,
                                if (ok) R.string.editor_saved else R.string.editor_save_failed,
                                Toast.LENGTH_SHORT
                            ).show()
                            if (ok) {
                                setResult(RESULT_OK)
                                finish()
                            } else {
                                saving = false
                            }
                        }
                    },
                )
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
    }

    private fun write(uri: Uri, bitmap: Bitmap): Boolean = runCatching {
        // "wt" = truncate; without it a smaller (cropped) PNG would leave stale trailing bytes.
        contentResolver.openOutputStream(uri, "wt")?.use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } == true
    }.onFailure {
    }.getOrDefault(false)

    /** Decodes at a memory-safe size (same approach as ReFra's CropActivity). */
    private fun decode(uri: Uri): Bitmap? = runCatching {
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(contentResolver, uri)) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val longest = max(info.size.width, info.size.height)
            if (longest > MAX_DIMENSION) {
                decoder.setTargetSampleSize(ceil(longest.toFloat() / MAX_DIMENSION).toInt().coerceAtLeast(1))
            }
        }
    }.recoverCatching {
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
    }.getOrNull()

    private companion object {
        const val MAX_DIMENSION = 4096
    }
}
