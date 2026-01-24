package com.nostr.unfiltered.nostr

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import rust.nostr.protocol.EventBuilder
import rust.nostr.protocol.Kind
import rust.nostr.protocol.Tag
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

    suspend fun uploadImage(
        context: Context,
        imageUri: Uri
    ): Result<UploadResult> = withContext(Dispatchers.IO) {
        try {
            // Read image bytes
            val inputStream = context.contentResolver.openInputStream(imageUri)
                ?: return@withContext Result.failure(Exception("Could not open image"))

            val bytes = inputStream.use { it.readBytes() }

            // Get mime type
            val mimeType = context.contentResolver.getType(imageUri) ?: "image/jpeg"

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
}
