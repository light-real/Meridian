package com.tradesim.app.core.data.supabase

import com.tradesim.app.core.data.AppException
import com.tradesim.app.core.data.network.HttpClient
import com.tradesim.app.core.data.network.HttpException
import com.tradesim.app.core.data.session.Session
import java.io.IOException
import java.time.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

sealed interface SignUpResult {
    data class SignedIn(val session: Session) : SignUpResult
    data object NeedsConfirmation : SignUpResult
}

@Serializable
data class CloudUserData(
    @SerialName("user_id") val userId: String,
    val cash: JsonElement? = null,
    val stocks: JsonElement? = null,
    val history: JsonElement? = null,
    @SerialName("total_assets") val totalAssets: JsonElement? = null,
    val profit: JsonElement? = null,
)

@Serializable
private data class CloudFavorites(
    val symbols: JsonElement? = null,
)

@Serializable
private data class AuthResponse(
    @SerialName("access_token") val accessToken: String? = null,
    @SerialName("refresh_token") val refreshToken: String? = null,
    @SerialName("expires_in") val expiresIn: Long? = null,
    val user: AuthUser? = null,
    val id: String? = null,
    val email: String? = null,
)

@Serializable
data class AuthUser(
    val id: String,
    val email: String? = null,
)

class SupabaseClient(
    private val http: HttpClient,
    private val json: Json,
    rawBaseUrl: String,
    private val anonKey: String,
) {
    private val baseUrl = rawBaseUrl.trimEnd('/')

    val isConfigured: Boolean get() = baseUrl.isNotBlank() && anonKey.isNotBlank()

    suspend fun signIn(email: String, password: String): Session {
        requireConfigured()
        val body = buildJsonObject {
            put("email", email)
            put("password", password)
        }.toString()
        val response = try {
            http.postJson(
                url = "$baseUrl/auth/v1/token?grant_type=password",
                body = body,
                headers = authHeaders(),
            )
        } catch (e: Exception) {
            throw mapAuthError(e)
        }
        val dto = decodeAuth(response)
        val user = dto.user ?: AuthUser(id = dto.id ?: "", email = dto.email ?: email)
        return sessionFrom(dto, fallbackUserId = user.id, fallbackEmail = user.email ?: email)
    }

    suspend fun signUp(email: String, password: String): SignUpResult {
        requireConfigured()
        val body = buildJsonObject {
            put("email", email)
            put("password", password)
        }.toString()
        val response = try {
            http.postJson(
                url = "$baseUrl/auth/v1/signup",
                body = body,
                headers = authHeaders(),
            )
        } catch (e: Exception) {
            throw mapAuthError(e)
        }
        val dto = decodeAuth(response)
        if (dto.accessToken.isNullOrBlank()) {
            return SignUpResult.NeedsConfirmation
        }
        val user = dto.user ?: AuthUser(id = dto.id ?: "", email = dto.email ?: email)
        return SignUpResult.SignedIn(
            sessionFrom(dto, fallbackUserId = user.id, fallbackEmail = user.email ?: email),
        )
    }

    suspend fun refresh(session: Session): Session {
        requireConfigured()
        val body = buildJsonObject {
            put("refresh_token", session.refreshToken)
        }.toString()
        val response = try {
            http.postJson(
                url = "$baseUrl/auth/v1/token?grant_type=refresh_token",
                body = body,
                headers = authHeaders(),
            )
        } catch (e: Exception) {
            throw mapAuthError(e)
        }
        val dto = decodeAuth(response)
        return sessionFrom(
            dto = dto,
            fallbackUserId = session.userId,
            fallbackEmail = session.email,
        )
    }

    suspend fun signOut(session: Session) {
        if (!isConfigured) return
        runCatching {
            http.postJson(
                url = "$baseUrl/auth/v1/logout",
                body = "{}",
                headers = dataHeaders(session.accessToken),
            )
        }
    }

    suspend fun loadUserData(session: Session): CloudUserData? {
        requireConfigured()
        val body = try {
            http.get(
                url = "$baseUrl/rest/v1/user_data?user_id=eq.${session.userId}&select=*",
                headers = dataHeaders(session.accessToken),
            )
        } catch (e: Exception) {
            throw mapDataError(e)
        }
        return runCatching {
            json.decodeFromString<List<CloudUserData>>(body).firstOrNull()
        }.getOrNull()
    }

    suspend fun saveUserData(session: Session, payload: JsonObject) {
        requireConfigured()
        val body = buildJsonObject {
            put("user_id", session.userId)
            payload.forEach { (key, value) -> put(key, value) }
        }
        try {
            http.postJson(
                url = "$baseUrl/rest/v1/user_data?on_conflict=user_id",
                body = body.toString(),
                headers = dataHeaders(session.accessToken) + mapOf(
                    "Prefer" to "resolution=merge-duplicates,return=minimal",
                ),
            )
        } catch (e: Exception) {
            throw mapDataError(e)
        }
    }

    suspend fun loadFavorites(session: Session): List<String>? {
        requireConfigured()
        val body = try {
            http.get(
                url = "$baseUrl/rest/v1/favorite_symbols?user_id=eq.${session.userId}&select=symbols",
                headers = dataHeaders(session.accessToken),
            )
        } catch (e: Exception) {
            throw mapDataError(e)
        }
        val dto = runCatching {
            json.decodeFromString<List<CloudFavorites>>(body).firstOrNull()
        }.getOrNull() ?: return null
        val array = dto.symbols as? JsonArray ?: return null
        return array.mapNotNull { it.jsonPrimitive.contentOrNull }
    }

    suspend fun saveFavorites(session: Session, symbols: List<String>) {
        requireConfigured()
        val body = buildJsonObject {
            put("user_id", session.userId)
            put(
                "symbols",
                buildJsonArray {
                    symbols.distinct().forEach { add(JsonPrimitive(it)) }
                },
            )
            put("updated_at", Instant.now().toString())
        }
        try {
            http.postJson(
                url = "$baseUrl/rest/v1/favorite_symbols?on_conflict=user_id",
                body = body.toString(),
                headers = dataHeaders(session.accessToken) + mapOf(
                    "Prefer" to "resolution=merge-duplicates,return=minimal",
                ),
            )
        } catch (e: Exception) {
            throw mapDataError(e)
        }
    }

    private fun decodeAuth(response: String): AuthResponse =
        runCatching { json.decodeFromString<AuthResponse>(response) }
            .getOrElse { throw AppException("服务返回格式异常", it) }

    private fun sessionFrom(
        dto: AuthResponse,
        fallbackUserId: String,
        fallbackEmail: String,
    ): Session {
        val accessToken = dto.accessToken?.takeIf { it.isNotBlank() }
            ?: throw AppException("登录失败，请稍后重试")
        return Session(
            accessToken = accessToken,
            refreshToken = dto.refreshToken ?: "",
            userId = fallbackUserId,
            email = fallbackEmail,
            expiresAt = System.currentTimeMillis() + (dto.expiresIn ?: 3600L) * 1000L,
        )
    }

    private fun requireConfigured() {
        if (!isConfigured) {
            throw AppException("未配置 Supabase，当前只能使用游客模式")
        }
    }

    private fun authHeaders(): Map<String, String> = buildMap {
        put("apikey", anonKey)
        put("Accept", "application/json")
        put("Content-Type", "application/json")
    }

    private fun dataHeaders(accessToken: String): Map<String, String> = buildMap {
        put("apikey", anonKey)
        put("Authorization", "Bearer $accessToken")
        put("Accept", "application/json")
        put("Content-Type", "application/json")
    }

    private fun mapAuthError(error: Throwable): AppException {
        if (error is AppException) return error
        if (error is HttpException) {
            val message = extractMessage(error.body)
            val translated = translateAuthMessage(message)
            return AppException(
                translated ?: when (error.code) {
                    429 -> "操作过于频繁，请稍后再试"
                    in 500..599 -> "服务暂时不可用，请稍后重试"
                    else -> "登录失败，请检查邮箱和密码"
                },
                error,
            )
        }
        return mapDataError(error)
    }

    private fun mapDataError(error: Throwable): AppException {
        if (error is AppException) return error
        if (error is HttpException) {
            val message = extractMessage(error.body)
            return AppException(
                when (error.code) {
                    401, 403 -> "登录已过期，请重新登录"
                    429 -> "操作过于频繁，请稍后再试"
                    in 500..599 -> "服务暂时不可用，请稍后重试"
                    else -> message ?: "服务异常（${error.code}）"
                },
                error,
            )
        }
        if (error is IOException) {
            return AppException("网络异常，请检查网络后重试", error)
        }
        return AppException("操作失败，请稍后重试", error)
    }

    private fun extractMessage(body: String?): String? {
        if (body.isNullOrBlank()) return null
        val obj = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return null
        val keys = listOf("msg", "message", "error_description", "error")
        return keys.firstNotNullOfOrNull { key ->
            (obj[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
        }
    }

    private fun translateAuthMessage(message: String?): String? {
        if (message.isNullOrBlank()) return null
        return when {
            message.contains("Invalid login credentials", ignoreCase = true) -> "邮箱或密码错误"
            message.contains("Email not confirmed", ignoreCase = true) -> "邮箱尚未确认，请先到邮箱完成确认"
            message.contains("User already registered", ignoreCase = true) -> "该邮箱已注册，请直接登录"
            message.contains("Password should be at least", ignoreCase = true) -> "密码至少需要 6 位"
            message.contains("For security purposes", ignoreCase = true) -> "操作过于频繁，请稍后再试"
            else -> message
        }
    }
}
