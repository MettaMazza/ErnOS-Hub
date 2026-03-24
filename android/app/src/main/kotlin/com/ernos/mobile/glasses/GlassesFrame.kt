package com.ernos.mobile.glasses

import android.graphics.Bitmap

/**
 * GlassesFrame
 *
 * A single captured frame from Meta Ray-Ban glasses.
 *
 * @param jpeg      JPEG-compressed bytes suitable for display (Compose BitmapFactory)
 *                  and for encoding as base64 image tokens for Qwen 3.5.
 * @param timestamp System.currentTimeMillis() when the frame was captured.
 * @param width     Pixel width of the frame (from the glasses camera).
 * @param height    Pixel height of the frame.
 */
data class GlassesFrame(
    val jpeg:      ByteArray,
    val timestamp: Long = System.currentTimeMillis(),
    val width:     Int  = 0,
    val height:    Int  = 0,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is GlassesFrame) return false
        return timestamp == other.timestamp && jpeg.contentEquals(other.jpeg)
    }

    override fun hashCode(): Int = 31 * timestamp.hashCode() + jpeg.contentHashCode()
}
