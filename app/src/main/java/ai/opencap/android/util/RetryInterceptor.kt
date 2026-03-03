package ai.opencap.android.util

import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException

class RetryInterceptor(private val retryCount: Int) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        var lastException: IOException? = null
        repeat(retryCount + 1) { attempt ->
            try {
                return chain.proceed(chain.request())
            } catch (io: IOException) {
                lastException = io
                if (attempt < retryCount) {
                    Thread.sleep(2000)
                }
            }
        }
        throw lastException ?: IOException("Unknown network error")
    }
}
