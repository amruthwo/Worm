package com.worm.app.wormhole

import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size as GSize
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.zxing.BarcodeFormat
import androidx.compose.foundation.layout.fillMaxSize
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.EncodeHintType
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.util.concurrent.Executors

/** Draws [text] as a QR code with rounded "pixel" modules in Worm colours. */
/**
 * Solid dark modules (rounded just a touch) with dark finder squares, so cameras lock on fast,
 * and the Worm logo in the middle. High error correction covers the modules the logo hides.
 */
@Composable
fun QrCode(text: String, modifier: Modifier = Modifier, dark: Color = Color(0xFF2A0E3A), accent: Color = Color(0xFFD552A3)) {
    val matrix = remember(text) {
        QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.H, EncodeHintType.MARGIN to 0))
    }
    val n = matrix.width
    // Logo hole: about a fifth of the width, odd-sized and centred on the module grid.
    val hole = ((n * 0.2f).toInt() or 1).coerceAtLeast(5)
    val h0 = (n - hole) / 2
    androidx.compose.foundation.layout.Box(modifier, contentAlignment = androidx.compose.ui.Alignment.Center) {
        Canvas(Modifier.matchParentSize()) {
            val cell = size.minDimension / n
            fun inFinder(x: Int, y: Int) = (x < 7 && y < 7) || (x >= n - 7 && y < 7) || (x < 7 && y >= n - 7)
            fun inHole(x: Int, y: Int) = x in h0 until h0 + hole && y in h0 until h0 + hole
            for (y in 0 until n) for (x in 0 until n) {
                if (matrix.get(x, y) && !inFinder(x, y) && !inHole(x, y)) {
                    drawRoundRect(dark, Offset(x * cell, y * cell), GSize(cell * 1.01f, cell * 1.01f), CornerRadius(cell * 0.18f))
                }
            }
            listOf(0 to 0, n - 7 to 0, 0 to n - 7).forEach { (fx, fy) ->
                val o = Offset(fx * cell, fy * cell)
                drawRoundRect(dark, o + Offset(cell / 2, cell / 2), GSize(cell * 6, cell * 6), CornerRadius(cell * 1.6f), style = Stroke(cell))
                drawRoundRect(dark, o + Offset(cell * 2, cell * 2), GSize(cell * 3, cell * 3), CornerRadius(cell * 0.8f))
            }
        }
        androidx.compose.foundation.Image(
            androidx.compose.ui.res.painterResource(com.worm.app.R.drawable.worm_logo), null,
            Modifier.fillMaxSize((hole - 1f) / n),
        )
    }
}

/** Camera preview that reports the first QR code it reads. */
@Composable
fun QrScanner(onResult: (String) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val callback = rememberUpdatedState(onResult)
    val executor = remember { Executors.newSingleThreadExecutor() }
    val previewView = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }
    DisposableEffect(owner) {
        val future = ProcessCameraProvider.getInstance(context)
        var provider: ProcessCameraProvider? = null
        future.addListener({
            provider = future.get()
            val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
            val reader = QRCodeReader()
            val hints = mapOf(DecodeHintType.TRY_HARDER to true, DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE))
            val analysis = ImageAnalysis.Builder()
                .setTargetResolution(Size(1280, 720))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            analysis.setAnalyzer(executor) { image ->
                try {
                    val plane = image.planes[0]
                    val buf = plane.buffer
                    val data = ByteArray(buf.remaining()).also { buf.get(it) }
                    val source = PlanarYUVLuminanceSource(data, plane.rowStride, image.height, 0, 0, image.width, image.height, false)
                    // Two binarizers, then the inverted image: covers glare, dim rooms and dark-mode screens.
                    val result = listOf<() -> com.google.zxing.Binarizer>(
                        { HybridBinarizer(source) }, { com.google.zxing.common.GlobalHistogramBinarizer(source) }, { HybridBinarizer(source.invert()) },
                    ).firstNotNullOfOrNull { b -> runCatching { reader.decode(BinaryBitmap(b()), hints) }.getOrNull().also { reader.reset() } }
                    if (result != null) ContextCompat.getMainExecutor(context).execute { callback.value(result.text) }
                } finally {
                    image.close()
                }
            }
            runCatching {
                provider?.unbindAll()
                provider?.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            }
        }, ContextCompat.getMainExecutor(context))
        onDispose {
            provider?.unbindAll()
            executor.shutdown()
        }
    }
    AndroidView({ previewView }, modifier)
}
