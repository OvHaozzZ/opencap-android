package ai.opencap.android.pose

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

class PoseModelManager(private val context: Context) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    fun ensureModel(variant: PoseModelVariant): File? {
        val modelDir = File(context.filesDir, "mediapipe_models")
        if (!modelDir.exists()) {
            modelDir.mkdirs()
        }
        val modelFile = File(modelDir, variant.fileName)
        if (modelFile.exists() && modelFile.length() > 1_000_000) {
            return modelFile
        }

        val request = Request.Builder()
            .url(variant.downloadUrl)
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                return null
            }
            val body = response.body ?: return null
            modelFile.outputStream().use { output ->
                body.byteStream().use { input ->
                    input.copyTo(output)
                }
            }
        }

        if (!modelFile.exists() || modelFile.length() <= 1_000_000) {
            throw IOException("Downloaded model file is invalid: ${modelFile.absolutePath}")
        }
        return modelFile
    }
}
