package com.subflow.input

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

// ML Kit OCR over a screenshot. bitmap gets recycled right after to keep memory down.
object ScreenshotParser {

    suspend fun extractText(context: Context, uri: Uri): String {
        val bitmap = loadBitmap(context, uri) ?: return ""
        return try {
            recognize(bitmap)
        } finally {
            bitmap.recycle()
        }
    }

    suspend fun extractText(bitmap: Bitmap): String = recognize(bitmap)

    private fun loadBitmap(context: Context, uri: Uri): Bitmap? = try {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
            decoder.isMutableRequired = false
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
    } catch (e: Exception) {
        try {
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
        } catch (e2: Exception) {
            null
        }
    }

    private suspend fun recognize(bitmap: Bitmap): String =
        suspendCancellableCoroutine { cont ->
            val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            val image = InputImage.fromBitmap(bitmap, 0)
            recognizer.process(image)
                .addOnSuccessListener { result ->
                    if (cont.isActive) cont.resume(result.text)
                }
                .addOnFailureListener {
                    if (cont.isActive) cont.resume("")
                }
                .addOnCompleteListener {
                    recognizer.close() // prevent native resource leak
                }
        }
}
