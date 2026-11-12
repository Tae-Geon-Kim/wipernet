package ai.wipernet.car.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/** 서버 API(docs/api.md). 연결 실패, 5xx, 잘못된 서버 주소는 IOException이다. */
class ServerApi(private val baseUrl: String, private val http: OkHttpClient = defaultHttp) {
    @Serializable private data class ClockJson(val now: String? = null)
    @Serializable private data class RainMapJson(val cells: List<RainCellJson>)
    @Serializable private data class RiskJson(val underpasses: List<UnderpassRiskJson>)
    @Serializable private data class EventsJson(val events: List<WiperEvent>)

    suspend fun clock(): String? = wireJson.decodeFromString<ClockJson>(get("/v1/clock")).now
    suspend fun rainMap(): List<RainCellJson> = wireJson.decodeFromString<RainMapJson>(get("/v1/rain-map")).cells
    suspend fun underpassRisk(): List<UnderpassRiskJson> =
        wireJson.decodeFromString<RiskJson>(get("/v1/underpass-risk")).underpasses

    /**
     * 이 묶음을 끝냈으면 true. 4xx는 다시 보내도 같은 답이라 버린다(true).
     * 연결 실패와 5xx는 false라 큐에 남는다.
     */
    suspend fun postEvents(events: List<WiperEvent>): Boolean = try {
        val body = wireJson.encodeToString(EventsJson(events)).toRequestBody("application/json".toMediaType())
        send(Request.Builder().url(url("/v1/wiper-events")).post(body).build()).first in 200..499
    } catch (e: IOException) {
        false
    }

    private suspend fun get(path: String): String {
        val (code, body) = send(Request.Builder().url(url(path)).build())
        if (code !in 200..299) throw IOException("HTTP $code $path")
        return body
    }

    private fun url(path: String): HttpUrl =
        baseUrl.trim().trimEnd('/').toHttpUrlOrNull()?.newBuilder()?.addPathSegments(path.trimStart('/'))?.build()
            ?: throw IOException("잘못된 서버 주소: $baseUrl")

    private suspend fun send(request: Request): Pair<Int, String> = withContext(Dispatchers.IO) {
        http.newCall(request).execute().use { r ->
            if (r.code >= 500) throw IOException("HTTP ${r.code} ${request.url.encodedPath}")
            r.code to r.body!!.string()
        }
    }

    private companion object {
        val defaultHttp: OkHttpClient = OkHttpClient.Builder().callTimeout(4, TimeUnit.SECONDS).build()
    }
}
