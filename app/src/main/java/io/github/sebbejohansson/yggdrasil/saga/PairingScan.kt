package io.github.sebbejohansson.yggdrasil.saga

import android.content.ClipboardManager
import android.content.Context
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning

/** Opens Google's scan screen for one QR code; [onText] gets what it read, [onError] why it didn't. */
fun scanPairingCode(context: Context, onText: (String) -> Unit, onError: (String) -> Unit) {
    val options = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
    GmsBarcodeScanning.getClient(context, options).startScan()
        .addOnSuccessListener { onText(it.rawValue.orEmpty()) }
        .addOnFailureListener { onError("Couldn't scan: ${it.message ?: "cancelled"}. Paste the code instead.") }
}

/** The copied text, for a pairing link pasted instead of scanned. */
fun clipboardText(context: Context): String =
    (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
        .primaryClip?.getItemAt(0)?.text?.toString().orEmpty()
