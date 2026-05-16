package com.macromind.foodscanner

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import androidx.exifinterface.media.ExifInterface

/**
 * ImagePickerHelper.kt
 *
 * Handles picking an image from the device gallery and loading it
 * as a correctly-rotated, memory-safe Bitmap.
 *
 * Usage:
 *   1. Call openGallery(activity) on button click.
 *   2. In onActivityResult, check requestCode == GALLERY_REQUEST_CODE,
 *      then call loadBitmapFromUri(context, data?.data).
 */
object ImagePickerHelper {

    const val GALLERY_REQUEST_CODE = 1001

    // Largest dimension we load — prevents OOM on high-res photos
    private const val MAX_DIMENSION = 1920

    /** Launch the system image picker */
    fun openGallery(activity: Activity) {
        val intent = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
        intent.type = "image/*"
        activity.startActivityForResult(intent, GALLERY_REQUEST_CODE)
    }

    /**
     * Convert the URI returned by the gallery picker into a Bitmap.
     * Returns null if loading fails for any reason.
     */
    fun loadBitmapFromUri(context: Context, uri: Uri): Bitmap? {
        return try {
            // Pass 1 — read only width/height, no pixels yet
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, bounds)
            }

            // Calculate how much to downsample
            val sampleSize = calcSampleSize(bounds.outWidth, bounds.outHeight)

            // Pass 2 — load downsampled bitmap
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inJustDecodeBounds = false
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val bitmap = context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, opts)
            } ?: return null

            // Apply EXIF rotation so the image is right-side up
            applyExifRotation(context, uri, bitmap)

        } catch (e: Exception) {
            Log.e("MacroMind", "loadBitmapFromUri failed: ${e.message}")
            null
        }
    }

    private fun applyExifRotation(context: Context, uri: Uri, bitmap: Bitmap): Bitmap {
        val degrees = try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val exif = ExifInterface(stream)
                when (exif.getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL
                )) {
                    ExifInterface.ORIENTATION_ROTATE_90  -> 90f
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else -> 0f
                }
            } ?: 0f
        } catch (e: Exception) {
            Log.w("MacroMind", "EXIF read failed: ${e.message}")
            0f
        }

        if (degrees == 0f) return bitmap

        return try {
            val matrix = Matrix().apply { postRotate(degrees) }
            val rotated = Bitmap.createBitmap(
                bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true
            )
            bitmap.recycle()
            rotated
        } catch (e: Exception) {
            bitmap // fallback — return original if rotation fails
        }
    }

    private fun calcSampleSize(width: Int, height: Int): Int {
        var sample = 1
        val longest = maxOf(width, height)
        while (longest / (sample * 2) >= MAX_DIMENSION) {
            sample *= 2
        }
        return sample
    }
}
