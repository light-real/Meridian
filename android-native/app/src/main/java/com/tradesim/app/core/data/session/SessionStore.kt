package com.tradesim.app.core.data.session

import com.tradesim.app.core.AppConstants
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

@Serializable
data class Session(
    val accessToken: String,
    val refreshToken: String,
    val userId: String,
    val email: String,
    val expiresAt: Long,
)

class SessionStore(
    private val securePrefs: SecurePrefs,
    private val json: Json,
) {
    private val _session = MutableStateFlow(load())
    val session: StateFlow<Session?> = _session.asStateFlow()

    val current: Session? get() = _session.value

    fun save(session: Session) {
        securePrefs.put(KEY_SESSION, json.encodeToString(Session.serializer(), session))
        _session.value = session
    }

    fun clear() {
        securePrefs.remove(KEY_SESSION)
        _session.value = null
    }

    fun isExpired(session: Session): Boolean =
        session.expiresAt <= System.currentTimeMillis() + 60_000L

    private fun load(): Session? {
        val raw = securePrefs.get(KEY_SESSION) ?: return null
        return runCatching {
            json.decodeFromString(Session.serializer(), raw)
        }.getOrNull()
    }

    private companion object {
        const val KEY_SESSION = "supabase_session"
    }
}
