package ai.opencap.android.data

import ai.opencap.android.model.PatchVideoRequest
import ai.opencap.android.model.SessionStatusResponse
import ai.opencap.android.model.VideoCredentials
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.Url

interface SessionApi {
    @GET
    suspend fun fetchSessionStatus(@Url url: String): SessionStatusResponse

    @GET
    suspend fun fetchVideoCredentials(@Url url: String): VideoCredentials

    @PATCH
    suspend fun patchVideo(
        @Url url: String,
        @Body payload: PatchVideoRequest
    ): Response<ResponseBody>
}
