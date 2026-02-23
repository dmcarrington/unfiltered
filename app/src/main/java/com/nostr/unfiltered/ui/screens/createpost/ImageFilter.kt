package com.nostr.unfiltered.ui.screens.createpost

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint

enum class ImageFilter(val displayName: String, val colorMatrix: ColorMatrix) {

    NONE("Normal", ColorMatrix()),

    CLARENDON("Clarendon", ColorMatrix(floatArrayOf(
        1.2f, 0f, 0f, 0f, 10f,
        0f, 1.2f, 0f, 0f, 10f,
        0f, 0f, 1.3f, 0f, 20f,
        0f, 0f, 0f, 1f, 0f
    ))),

    GINGHAM("Gingham", ColorMatrix(floatArrayOf(
        0.8f, 0.1f, 0.1f, 0f, 30f,
        0.1f, 0.8f, 0.1f, 0f, 30f,
        0.1f, 0.1f, 0.8f, 0f, 30f,
        0f, 0f, 0f, 1f, 0f
    ))),

    MOON("Moon", ColorMatrix(floatArrayOf(
        0.33f, 0.59f, 0.11f, 0f, 20f,
        0.33f, 0.59f, 0.11f, 0f, 20f,
        0.33f, 0.59f, 0.11f, 0f, 20f,
        0f, 0f, 0f, 1f, 0f
    )).apply {
        val contrast = ColorMatrix(floatArrayOf(
            1.4f, 0f, 0f, 0f, -30f,
            0f, 1.4f, 0f, 0f, -30f,
            0f, 0f, 1.4f, 0f, -30f,
            0f, 0f, 0f, 1f, 0f
        ))
        postConcat(contrast)
    }),

    LARK("Lark", ColorMatrix(floatArrayOf(
        1.2f, 0.1f, 0f, 0f, 20f,
        0f, 1.1f, 0.1f, 0f, 15f,
        0f, 0f, 0.9f, 0f, 10f,
        0f, 0f, 0f, 1f, 0f
    ))),

    REYES("Reyes", ColorMatrix(floatArrayOf(
        0.85f, 0.1f, 0.05f, 0f, 30f,
        0.05f, 0.85f, 0.1f, 0f, 25f,
        0.05f, 0.05f, 0.8f, 0f, 20f,
        0f, 0f, 0f, 0.9f, 0f
    ))),

    JUNO("Juno", ColorMatrix(floatArrayOf(
        1.3f, 0f, 0f, 0f, 15f,
        0f, 1.1f, 0f, 0f, 10f,
        0f, 0f, 0.9f, 0f, -10f,
        0f, 0f, 0f, 1f, 0f
    ))),

    VALENCIA("Valencia", ColorMatrix(floatArrayOf(
        1.1f, 0.1f, 0f, 0f, 20f,
        0f, 1.0f, 0.05f, 0f, 15f,
        0f, 0f, 0.8f, 0f, 5f,
        0f, 0f, 0f, 1f, 0f
    ))),

    XPRO2("X-Pro II", ColorMatrix(floatArrayOf(
        1.4f, 0f, 0f, 0f, -20f,
        0f, 1.1f, 0f, 0f, -10f,
        0f, 0f, 0.8f, 0f, 20f,
        0f, 0f, 0f, 1f, 0f
    ))),

    LOFI("Lo-Fi", ColorMatrix(floatArrayOf(
        1.4f, 0f, 0f, 0f, -15f,
        0f, 1.4f, 0f, 0f, -15f,
        0f, 0f, 1.4f, 0f, -15f,
        0f, 0f, 0f, 1f, 0f
    )).apply {
        val saturation = ColorMatrix()
        saturation.setSaturation(1.3f)
        postConcat(saturation)
    });
}

fun applyFilter(bitmap: Bitmap, filter: ImageFilter): Bitmap {
    if (filter == ImageFilter.NONE) return bitmap

    val result = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(result)
    val paint = Paint().apply {
        colorFilter = ColorMatrixColorFilter(filter.colorMatrix)
    }
    canvas.drawBitmap(bitmap, 0f, 0f, paint)
    return result
}
