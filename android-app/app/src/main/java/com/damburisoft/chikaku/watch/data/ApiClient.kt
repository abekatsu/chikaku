package com.damburisoft.chikaku.watch.data

import com.damburisoft.chikaku.watch.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.logging.HttpLoggingInterceptor
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 送信結果。呼び出し側は「捨てる」「後でやり直す」「ペアリングし直す」を
 * 区別する必要があるため、単なる成否ではなく理由まで返す。
 */
sealed interface ApiResult<out T> {
    data class Success<T>(val value: T) : ApiResult<T>

    /** 通信できなかった（圏外・DNS失敗・タイムアウト等）。時間をおいて再試行する。 */
    data class NetworkError(val cause: Throwable) : ApiResult<Nothing>

    /** サーバーが 5xx を返した。再試行する。 */
    data class ServerError(val code: Int, val body: String?) : ApiResult<Nothing>

    /** 認証が通らない。ペアリングをやり直す必要がある。 */
    data object Unauthorized : ApiResult<Nothing>

    /** リクエスト自体が不正（4xx）。再試行しても無駄なので捨てる。 */
    data class ClientError(val code: Int, val message: String?) : ApiResult<Nothing>
}

class ApiClient(private val settings: SettingsStore) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .apply {
                if (BuildConfig.DEBUG) {
                    // 位置情報が本文に含まれるため、リリースビルドでは絶対に有効化しない。
                    addInterceptor(
                        HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC }
                    )
                }
            }
            .build()
    }

    suspend fun registerDevice(
        baseUrl: String,
        inviteCode: String,
        deviceName: String,
        deviceModel: String,
    ): ApiResult<RegisterDeviceResponse> {
        val url = baseUrl.resolve("api/v1/devices/register")
            ?: return ApiResult.ClientError(0, "サーバーURLの形式が正しくありません")
        val body = json.encodeToString(
            RegisterDeviceRequest(inviteCode = inviteCode, deviceName = deviceName, deviceModel = deviceModel)
        )
        return execute(Request.Builder().url(url).post(body.toRequestBody(JSON_MEDIA_TYPE)).build()) {
            json.decodeFromString<RegisterDeviceResponse>(it)
        }
    }

    /**
     * 位置情報を1件送る。まとめ送りにしないのは、サーバー側の契約
     * （CLAUDE.md §3.2 の POST /api/v1/location）を単純に保つため。
     * キューの消化はワーカー側でこれを繰り返して行う。
     */
    suspend fun postLocation(payload: LocationPayload): ApiResult<Unit> {
        val current = settings.current()
        // トークンだけは別の置き場所にあり、ロック解除前は読めない (Issue #3)。
        // 送信経路は解除後にしか動かないため、ここが null になるのは
        // 未ペアリングか引き継ぎ失敗のときだけ。
        val token = settings.deviceToken() ?: return ApiResult.Unauthorized
        val url = current.serverBaseUrl.resolve("api/v1/location")
            ?: return ApiResult.ClientError(0, "サーバーURLの形式が正しくありません")
        val body = json.encodeToString(payload)
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .build()
        return execute(request) { }
    }

    private suspend fun <T> execute(request: Request, parse: (String) -> T): ApiResult<T> =
        withContext(Dispatchers.IO) {
            try {
                http.newCall(request).execute().use { response ->
                    val text = response.body.string()
                    when {
                        response.isSuccessful -> try {
                            ApiResult.Success(parse(text))
                        } catch (e: Exception) {
                            ApiResult.ClientError(response.code, "応答を解釈できませんでした: ${e.message}")
                        }

                        response.code == 401 || response.code == 403 -> ApiResult.Unauthorized
                        response.code >= 500 -> ApiResult.ServerError(response.code, text)
                        // 408/429 は一時的なものなので再試行対象に寄せる。
                        response.code == 408 || response.code == 429 ->
                            ApiResult.ServerError(response.code, text)

                        else -> ApiResult.ClientError(response.code, errorMessage(text) ?: text.take(200))
                    }
                }
            } catch (e: IOException) {
                ApiResult.NetworkError(e)
            }
        }

    private fun errorMessage(body: String): String? = try {
        json.decodeFromString<ErrorResponse>(body).let { it.message ?: it.error }
    } catch (_: Exception) {
        null
    }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}

/**
 * ベースURLに末尾スラッシュがあってもなくても同じ結果になるよう相対パスを解決する。
 * TLS必須（CLAUDE.md §5）のため https 以外は debug ビルドでのみ許可する。
 */
internal fun String.resolve(path: String): HttpUrl? {
    val base = (if (endsWith("/")) this else "$this/").toHttpUrlOrNull() ?: return null
    if (base.scheme != "https" && !BuildConfig.DEBUG) return null
    return base.resolve(path)
}
