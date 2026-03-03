package ai.opencap.android.data

import ai.opencap.android.model.VideoCredentials
import ai.opencap.android.util.RetryInterceptor
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okio.Buffer
import okio.BufferedSink
import okio.source
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

class S3Uploader {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(180, TimeUnit.SECONDS)
        .addInterceptor(RetryInterceptor(retryCount = 2))
        .build()

    fun uploadVideoToS3(
        file: File,
        credentials: VideoCredentials,
        onProgress: (Double) -> Unit,
        onDone: (Result<Unit>) -> Unit
    ): Call {
        val videoBody = ProgressFileRequestBody(
            file = file,
            contentType = "video/mp4",
            onProgress = onProgress
        )

        val multipart = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("key", credentials.fields.key)
            .addFormDataPart("AWSAccessKeyId", credentials.fields.accessKeyId)
            .addFormDataPart("policy", credentials.fields.policy)
            .addFormDataPart("signature", credentials.fields.signature)
            .addFormDataPart("file", credentials.fields.key, videoBody)
            .build()

        val request = Request.Builder()
            .url(credentials.url)
            .post(multipart)
            .build()

        val call = client.newCall(request)
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                onDone(Result.failure(e))
            }

            override fun onResponse(call: Call, response: Response) {
                if (response.isSuccessful) {
                    onDone(Result.success(Unit))
                } else {
                    onDone(
                        Result.failure(
                            IOException("S3 upload failed: HTTP ${response.code}")
                        )
                    )
                }
                response.close()
            }
        })
        return call
    }
}

private class ProgressFileRequestBody(
    private val file: File,
    private val contentType: String,
    private val onProgress: (Double) -> Unit
) : RequestBody() {
    override fun contentType() = contentType.toMediaType()

    override fun contentLength(): Long = file.length()

    override fun writeTo(sink: BufferedSink) {
        val source = file.source()
        source.use {
            val totalBytes = contentLength().toDouble().coerceAtLeast(1.0)
            var uploaded = 0L
            var read: Long
            val buffer = Buffer()
            while (true) {
                read = source.read(buffer, DEFAULT_BUFFER_SIZE.toLong())
                if (read == -1L) break
                sink.write(buffer, read)
                uploaded += read
                onProgress((uploaded / totalBytes).coerceIn(0.0, 1.0))
            }
        }
    }
}
