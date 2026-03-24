package com.ernos.mobile.glasses

import android.util.Base64
import android.util.Log
import org.json.JSONObject

/**
 * VisionEncoder
 *
 * Converts a [GlassesFrame] (JPEG bytes) into the token representation
 * expected by Qwen 3.5's native early-fusion multimodal encoder.
 *
 * Qwen 3.5-VL uses a special `<img>` tag with a base64 data-URI body:
 *
 *   <img>data:image/jpeg;base64,<base64EncodedJpeg></img>
 *
 * This tag is inserted directly into the prompt text where image content
 * is expected.  No external CLIP or LLaVA projector is used — Qwen 3.5's
 * built-in vision encoder processes the pixels end-to-end.
 *
 * Reference: Qwen-VL Technical Report §3.2 "Image Input Format"
 */
object VisionEncoder {

    private const val TAG      = "VisionEncoder"
    private const val MAX_JPEG_BYTES = 512 * 1024   // 512 KB — cap before base64 expansion

    /**
     * Encode [frame] as a Qwen-VL image token string suitable for insertion
     * into a prompt.
     *
     * @param frame    The glasses frame to encode.
     * @param maxBytes JPEG size cap before encoding. Frames larger than this are
     *                 logged and still encoded, but the prompt may be very long.
     * @return A string of the form `<img>data:image/jpeg;base64,...</img>`
     */
    fun encodeFrame(
        frame: GlassesFrame,
        maxBytes: Int = MAX_JPEG_BYTES,
    ): String {
        if (frame.jpeg.isEmpty()) {
            Log.w(TAG, "encodeFrame called with empty JPEG bytes — returning empty string")
            return ""
        }
        if (frame.jpeg.size > maxBytes) {
            Log.w(TAG, "Frame JPEG is ${frame.jpeg.size} bytes (>${maxBytes}) — consider resizing")
        }

        val b64 = Base64.encodeToString(frame.jpeg, Base64.NO_WRAP)
        Log.d(TAG, "Encoded frame: ${frame.jpeg.size} bytes → ${b64.length} base64 chars")
        return "<img>data:image/jpeg;base64,$b64</img>"
    }

    /**
     * Build a describe_image tool call JSON result from a glasses frame.
     * Used by [ToolRegistry] to inject the current frame into the ReAct loop.
     *
     * Returns a JSON string of:
     * ```json
     * { "source": "glasses", "encoded": "<img>data:image/jpeg;base64,...</img>",
     *   "width": 640, "height": 480, "timestamp": 1234567890 }
     * ```
     */
    fun frameToToolResult(frame: GlassesFrame): String {
        val encoded = encodeFrame(frame)
        return JSONObject().apply {
            put("source",    "glasses")
            put("encoded",   encoded)
            put("width",     frame.width)
            put("height",    frame.height)
            put("timestamp", frame.timestamp)
        }.toString()
    }
}
