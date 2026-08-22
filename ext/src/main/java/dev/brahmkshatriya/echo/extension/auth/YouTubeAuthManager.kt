package dev.brahmkshatriya.echo.extension.auth

import dev.brahmkshatriya.echo.common.helpers.ClientException
import dev.brahmkshatriya.echo.common.models.User
import dev.brahmkshatriya.echo.extension.endpoints.EchoVisitorEndpoint
import dev.brahmkshatriya.echo.extension.endpoints.GoogleAccountResponse
import dev.brahmkshatriya.echo.extension.json
import dev.toastbits.ytmkt.impl.youtubei.YoutubeiApi
import dev.toastbits.ytmkt.impl.youtubei.YoutubeiAuthenticationState
import io.ktor.client.call.body
import io.ktor.client.request.headers
import io.ktor.client.request.request
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Headers
import io.ktor.http.headers
import kotlinx.serialization.json.Json


class YouTubeAuthManager(
    private val api: YoutubeiApi,
    private val visitorEndpoint: EchoVisitorEndpoint
) {
    private var authState: YoutubeiAuthenticationState? = null
    
    private var pendingCredentials: PendingCredentials? = null
    
    private data class PendingCredentials(
        val cookie: String,
        val auth: String,
        val userId: String
    )

    suspend fun ensureVisitorId(): Result<String> = runCatching {
        api.visitorId ?: run {
            val visitorId = visitorEndpoint.getVisitorId()
            api.visitorId = visitorId
            visitorId
        }
    }

    fun storeCredentialsForLazyInit(cookie: String, auth: String, userId: String) {
        pendingCredentials = PendingCredentials(cookie, auth, userId)
        println("Stored credentials for lazy authentication")
    }

    suspend fun login(cookie: String, auth: String, userId: String): Result<User> = runCatching {
        val authHeaders = headers {
            append("cookie", cookie)
            append("authorization", auth)
        }

        authState = YoutubeiAuthenticationState(api, authHeaders, userId.ifEmpty { null })
        api.userAuthState = authState

        ensureVisitorId().getOrNull()

        getCurrentUser().getOrThrow() ?: throw Exception("Failed to retrieve user information after login")
    }

    fun logout() {
        authState = null
        pendingCredentials = null
        api.userAuthState = null
        println("Logged out and cleared credentials")
    }

    suspend fun getCurrentUser(): Result<User?> = runCatching {
        val state = authState ?: return@runCatching null

        val response = api.client.request("https://music.youtube.com/getAccountSwitcherEndpoint") {
            headers {
                append("referer", "https://music.youtube.com/")
                state.headers.forEach { key, values ->
                    values.forEach { value -> append(key, value) }
                }
            }
        }
        
        val responseText = response.bodyAsText()
        val jsonText = if (responseText.startsWith(")]}'")) {
            responseText.substringAfter(")]}'")
        } else {
            responseText
        }
        
        val accountResponse = json.decodeFromString<GoogleAccountResponse>(jsonText)
        val userResponse = accountResponse.getUsers("", "").firstOrNull()

        userResponse?.copy(
            subtitle = userResponse.extras["email"]?.takeIf { it.isNotEmpty() }
                ?: userResponse.extras["channelHandle"]?.takeIf { it.isNotEmpty() }
                ?: "YouTube Music User",
            extras = userResponse.extras.toMutableMap().apply {
                put("isLoggedIn", "true")
                put("userService", "youtube_music")
                put("accountType", "google")
                put("lastUpdated", System.currentTimeMillis().toString())
            }
        )
    }

    suspend fun requireAuth(): YoutubeiAuthenticationState {
        api.userAuthState?.let { return it }
        
        authState?.let { return it }
        
        pendingCredentials?.let { creds ->
            println("Performing lazy authentication initialization")
            return login(creds.cookie, creds.auth, creds.userId)
                .getOrThrow()
                .let { 
                    pendingCredentials = null 
                    authState ?: api.userAuthState ?: throw ClientException.LoginRequired()
                }
        }
        
        throw ClientException.LoginRequired()
    }

    fun isAuthenticated(): Boolean = api.userAuthState != null || authState != null
}
