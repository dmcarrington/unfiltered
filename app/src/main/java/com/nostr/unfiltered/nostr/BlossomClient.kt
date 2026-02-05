package com.nostr.unfiltered.nostr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import java.io.ByteArrayInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.json.JSONArray
import rust.nostr.protocol.EventBuilder
import rust.nostr.protocol.Kind
import rust.nostr.protocol.Tag
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BlossomClient @Inject constructor(
    private val keyManager: KeyManager
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    // Default Blossom servers
    private val blossomServers = listOf(
        "https://blossom.primal.net",
        "https://nostr.download",
        "https://files.v0l.io"
    )

    data class UploadResult(
        val url: String,
        val sha256: String,
        val size: Long,
        val mimeType: String
    )

    /**
     * Data prepared for upload, including the unsigned auth event for Amber signing
     */
    data class PreparedUpload(
        val bytes: ByteArray,
        val sha256: String,
        val mimeType: String,
        val unsignedAuthEvent: String
    )

    /**
     * Prepare upload data and create unsigned authorization event for Amber signing
     */
    suspend fun prepareUpload(
        context: Context,
        imageUri: Uri
    ): Result<PreparedUpload> = withContext(Dispatchers.IO) {
        try {
            val inputStream = context.contentResolver.openInputStream(imageUri)
                ?: return@withContext Result.failure(Exception("Could not open image"))

            val rawBytes = inputStream.use { it.readBytes() }
            val mimeType = context.contentResolver.getType(imageUri) ?: "image/jpeg"

            // Strip EXIF metadata (location, camera info, etc.) for privacy
            val bytes = stripExifMetadata(rawBytes, mimeType)

            val sha256 = calculateSha256(bytes)
            val unsignedAuthEvent = createUnsignedAuthorizationEvent(sha256, mimeType, bytes.size.toLong())

            Result.success(PreparedUpload(bytes, sha256, mimeType, unsignedAuthEvent))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Complete upload with a signed authorization event (for Amber flow)
     */
    suspend fun uploadWithSignedAuth(
        preparedUpload: PreparedUpload,
        signedAuthEventJson: String
    ): Result<UploadResult> = withContext(Dispatchers.IO) {
        try {
            // Base64 encode the signed event
            val authHeader = android.util.Base64.encodeToString(
                signedAuthEventJson.toByteArray(),
                android.util.Base64.NO_WRAP
            )

            var lastError: Exception? = null
            for (server in blossomServers) {
                try {
                    val result = uploadToServer(
                        server,
                        preparedUpload.bytes,
                        preparedUpload.mimeType,
                        authHeader
                    )
                    return@withContext Result.success(result.copy(sha256 = preparedUpload.sha256))
                } catch (e: Exception) {
                    lastError = e
                    continue
                }
            }

            Result.failure(lastError ?: Exception("All servers failed"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Create unsigned authorization event JSON for Amber signing
     */
    private fun createUnsignedAuthorizationEvent(
        sha256: String,
        mimeType: String,
        size: Long
    ): String {
        val pubkey = keyManager.getPublicKeyHex() ?: ""
        val createdAt = System.currentTimeMillis() / 1000
        val expiration = createdAt + 300 // 5 minutes

        val tags = JSONArray().apply {
            put(JSONArray().apply { put("t"); put("upload") })
            put(JSONArray().apply { put("x"); put(sha256) })
            put(JSONArray().apply { put("expiration"); put(expiration.toString()) })
        }

        return JSONObject().apply {
            put("kind", 24242)
            put("pubkey", pubkey)
            put("created_at", createdAt)
            put("tags", tags)
            put("content", "Upload $mimeType")
        }.toString()
    }

    suspend fun uploadImage(
        context: Context,
        imageUri: Uri
    ): Result<UploadResult> = withContext(Dispatchers.IO) {
        try {
            // Read image bytes
            val inputStream = context.contentResolver.openInputStream(imageUri)
                ?: return@withContext Result.failure(Exception("Could not open image"))

            val rawBytes = inputStream.use { it.readBytes() }

            // Get mime type
            val mimeType = context.contentResolver.getType(imageUri) ?: "image/jpeg"

            // Strip EXIF metadata (location, camera info, etc.) for privacy
            val bytes = stripExifMetadata(rawBytes, mimeType)

            // Calculate SHA-256 hash
            val sha256 = calculateSha256(bytes)

            // Create authorization event (BUD-01)
            val authEvent = createAuthorizationEvent(sha256, mimeType, bytes.size.toLong())
                ?: return@withContext Result.failure(Exception("Could not create authorization - no keys"))

            // Try each server until one succeeds
            var lastError: Exception? = null
            for (server in blossomServers) {
                try {
                    val result = uploadToServer(server, bytes, mimeType, authEvent)
                    return@withContext Result.success(result.copy(sha256 = sha256))
                } catch (e: Exception) {
                    lastError = e
                    continue
                }
            }

            Result.failure(lastError ?: Exception("All servers failed"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun uploadToServer(
        serverUrl: String,
        bytes: ByteArray,
        mimeType: String,
        authEvent: String
    ): UploadResult {
        val requestBody = bytes.toRequestBody(mimeType.toMediaType())

        val request = Request.Builder()
            .url("$serverUrl/upload")
            .header("Authorization", "Nostr $authEvent")
            .header("Content-Type", mimeType)
            .put(requestBody)
            .build()

        val response = client.newCall(request).execute()

        if (!response.isSuccessful) {
            throw Exception("Upload failed: ${response.code} - ${response.body?.string()}")
        }

        val responseBody = response.body?.string()
            ?: throw Exception("Empty response from server")

        val json = JSONObject(responseBody)

        return UploadResult(
            url = json.getString("url"),
            sha256 = json.optString("sha256", ""),
            size = json.optLong("size", bytes.size.toLong()),
            mimeType = mimeType
        )
    }

    private fun createAuthorizationEvent(
        sha256: String,
        mimeType: String,
        size: Long
    ): String? {
        val keys = keyManager.getKeys() ?: return null

        // BUD-01: Authorization event for upload
        // Kind 24242 with tags: t=upload, x=sha256, expiration
        val expiration = (System.currentTimeMillis() / 1000) + 300 // 5 minutes

        val tags = listOf(
            Tag.parse(listOf("t", "upload")),
            Tag.parse(listOf("x", sha256)),
            Tag.parse(listOf("expiration", expiration.toString()))
        )

        val event = EventBuilder(Kind(24242u), "Upload $mimeType", tags)
            .toEvent(keys)

        // Return base64-encoded event JSON
        return android.util.Base64.encodeToString(
            event.asJson().toByteArray(),
            android.util.Base64.NO_WRAP
        )
    }

    private fun calculateSha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(bytes)
        return hashBytes.joinToString("") { "%02x".format(it) }
    }

    /**
     * Strip EXIF metadata from image bytes for privacy.
     * Removes location data, camera info, timestamps, and other sensitive metadata.
     * Preserves image orientation by applying rotation before re-encoding.
     */
    @Suppress("DEPRECATION")
    private fun stripExifMetadata(bytes: ByteArray, mimeType: String): ByteArray {
        return try {
            // Read EXIF orientation before decoding
            val orientation = try {
                val exif = ExifInterface(ByteArrayInputStream(bytes))
                exif.getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL
                )
            } catch (e: Exception) {
                ExifInterface.ORIENTATION_NORMAL
            }

            // Decode the image to a Bitmap (this naturally strips EXIF data)
            var bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                ?: return bytes // Return original if decode fails

            // Apply rotation based on EXIF orientation
            bitmap = applyExifOrientation(bitmap, orientation)

            // Determine output format based on mime type
            val format = when {
                mimeType.contains("png", ignoreCase = true) -> Bitmap.CompressFormat.PNG
                mimeType.contains("webp", ignoreCase = true) -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        Bitmap.CompressFormat.WEBP_LOSSY
                    } else {
                        Bitmap.CompressFormat.WEBP
                    }
                }
                else -> Bitmap.CompressFormat.JPEG
            }

            // Re-encode the bitmap without EXIF data (rotation is now baked in)
            val outputStream = ByteArrayOutputStream()
            val quality = if (format == Bitmap.CompressFormat.PNG) 100 else 95
            bitmap.compress(format, quality, outputStream)
            bitmap.recycle()

            outputStream.toByteArray()
        } catch (e: Exception) {
            // If stripping fails, return original bytes rather than failing the upload
            bytes
        }
    }

    /**
     * Apply EXIF orientation to a bitmap by rotating/flipping as needed.
     * Returns a new bitmap with the correct orientation.
     */
    private fun applyExifOrientation(bitmap: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()

        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postRotate(270f)
                matrix.postScale(-1f, 1f)
            }
            else -> return bitmap // No transformation needed
        }

        val rotatedBitmap = Bitmap.createBitmap(
            bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true
        )

        // Recycle original if a new bitmap was created
        if (rotatedBitmap != bitmap) {
            bitmap.recycle()
        }

        return rotatedBitmap
    }
}
