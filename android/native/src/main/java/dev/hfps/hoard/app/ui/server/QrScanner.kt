package dev.hfps.hoard.app.ui.server

import android.content.Context
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning

/**
 * Scans the pairing QR code (ADR 0021) with Google's code scanner: Play services show the camera, Hoard needs no
 * camera permission and never sees the image. Phones without Play services paste the link instead.
 */
object QrScanner {
    fun available(context: Context): Boolean =
        runCatching { context.packageManager.getPackageInfo("com.google.android.gms", 0); true }.getOrDefault(false)

    fun scan(context: Context, onResult: (String) -> Unit, onFailure: () -> Unit) {
        val options = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
        GmsBarcodeScanning.getClient(context, options).startScan()
            .addOnSuccessListener { code -> code.rawValue?.let(onResult) ?: onFailure() }
            .addOnFailureListener { onFailure() }
    }
}
