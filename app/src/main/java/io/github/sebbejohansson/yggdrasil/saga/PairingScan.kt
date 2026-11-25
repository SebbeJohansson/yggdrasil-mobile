package io.github.sebbejohansson.yggdrasil.saga

import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.sebbejohansson.yggdrasil.ui.Hud
import io.github.sebbejohansson.yggdrasil.ui.HudButton
import io.github.sebbejohansson.yggdrasil.ui.HudButtonKind
import io.github.sebbejohansson.yggdrasil.ui.HudText
import io.github.sebbejohansson.yggdrasil.ui.hudPanel
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The phone's own camera, in a dialog, reading one QR code. [onText] gets
 * what it read and the dialog should then be closed by the caller; [onClose]
 * is Cancel or a refused camera permission. The permission is asked for here,
 * when you tap Scan QR, not at install: pasting the link needs no camera.
 */
@Composable
fun PairScanner(onText: (String) -> Unit, onClose: (String?) -> Unit) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        granted = it
        if (!it) onClose("The camera isn't allowed, so there is nothing to scan with. Paste the code instead.")
    }
    LaunchedEffect(Unit) { if (!granted) ask.launch(Manifest.permission.CAMERA) }

    Dialog(onDismissRequest = { onClose(null) }) {
        Column(
            Modifier.fillMaxWidth().hudPanel().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Point the camera at the QR code from `ygg pair`.", style = HudText.meta.copy(color = Hud.overlay2))
            if (granted) CameraPreview(onText) else Text("Waiting for the camera permission…", style = HudText.meta)
            HudButton("Cancel", { onClose(null) }, kind = HudButtonKind.SMALL)
        }
    }
}

@Composable
private fun CameraPreview(onText: (String) -> Unit) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val executor = remember { Executors.newSingleThreadExecutor() }
    val done = remember { AtomicBoolean(false) }
    val view = remember { PreviewView(context) }
    DisposableEffect(owner) {
        val future = ProcessCameraProvider.getInstance(context)
        val listener = Runnable {
            val provider = future.get()
            val preview = Preview.Builder().build().also { it.surfaceProvider = view.surfaceProvider }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            analysis.setAnalyzer(executor) { image ->
                try {
                    val plane = image.planes[0]
                    val buf = plane.buffer
                    val bytes = ByteArray(buf.remaining()).also { buf.get(it) }
                    val text = decodeQr(bytes, plane.rowStride, image.width, image.height)
                    // Once: frames keep coming until the dialog is gone.
                    if (text != null && done.compareAndSet(false, true)) {
                        ContextCompat.getMainExecutor(context).execute { onText(text) }
                    }
                } finally {
                    image.close()
                }
            }
            provider.unbindAll()
            provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
        }
        future.addListener(listener, ContextCompat.getMainExecutor(context))
        onDispose {
            runCatching { future.get().unbindAll() }
            executor.shutdown()
        }
    }
    AndroidView({ view }, Modifier.fillMaxWidth().height(320.dp))
}

/** The copied text, for a pairing link pasted instead of scanned. */
fun clipboardText(context: Context): String =
    (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
        .primaryClip?.getItemAt(0)?.text?.toString().orEmpty()
