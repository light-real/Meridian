package com.tradesim.app.core.data.repository

import com.tradesim.app.core.data.session.Session
import com.tradesim.app.core.data.session.SessionStore
import com.tradesim.app.core.data.supabase.SignUpResult
import com.tradesim.app.core.data.supabase.SupabaseClient
import kotlinx.coroutines.flow.StateFlow

class AuthRepository(
    private val supabase: SupabaseClient,
    private val sessionStore: SessionStore,
) {
    val session: StateFlow<Session?> = sessionStore.session
    val isConfigured: Boolean get() = supabase.isConfigured

    fun currentSession(): Session? = sessionStore.current

    suspend fun signIn(email: String, password: String): Session {
        val session = supabase.signIn(email, password)
        sessionStore.save(session)
        return session
    }

    suspend fun signUp(email: String, password: String): SignUpResult {
        val result = supabase.signUp(email, password)
        if (result is SignUpResult.SignedIn) {
            sessionStore.save(result.session)
        }
        return result
    }

    suspend fun signOut() {
        sessionStore.current?.let { runCatching { supabase.signOut(it) } }
        sessionStore.clear()
    }

    /**
     * 返回仍然有效的 session；如果过期则尝试刷新。
     * 刷新失败会清空本地 session，调用方按未登录处理。
     */
    suspend fun ensureValidSession(): Session? {
        val current = sessionStore.current ?: return null
        if (!sessionStore.isExpired(current)) return current
        return runCatching {
            supabase.refresh(current).also { sessionStore.save(it) }
        }.getOrElse {
            sessionStore.clear()
            null
        }
    }

    fun clearSession() {
        sessionStore.clear()
    }
}
