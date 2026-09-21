package hu.apkforge

import android.content.Context
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

data class DeviceCode(
    val deviceCode: String,
    val userCode: String,
    val verificationUri: String,
    val expiresIn: Long,
    val intervalSeconds: Long,
)

data class AuthResult(
    val accessToken: String,
    val refreshToken: String,
    val expiresInSeconds: Long,
)

class GitHubAuth(private val context: Context, private val store: SecureStore) {
    private val http = OkHttpClient.Builder()
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val clientId = BuildConfig.GITHUB_CLIENT_ID
    private val deviceUrl = "https://github.com/login/device/code"
    private val tokenUrl = "https://github.com/login/oauth/access_token"

    fun isConfigured(): Boolean =
        clientId.isNotBlank() && !clientId.contains("PUT_YOUR_GITHUB_APP_CLIENT_ID_HERE")

    fun currentLogin(): String = store.login

    fun clear() = store.clear()

    fun openVerificationPage() {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/login/device"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    suspend fun requestDeviceCode(): Result<DeviceCode> = withContext(Dispatchers.IO) {
        runCatching {
            if (!isConfigured()) {
                throw IOException("Az APK Forge GitHub App Client ID-ja még nincs beállítva.")
            }

            val body = FormBody.Builder()
                .add("client_id", clientId)
                .build()

            val request = Request.Builder()
                .url(deviceUrl)
                .header("Accept", "application/json")
                .post(body)
                .build()

            http.newCall(request).execute().use { response ->
                val raw = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw IOException("GitHub bejelentkezési hiba (${response.code}).")
                }
                val json = JSONObject(raw)
                if (json.has("error")) {
                    throw IOException(
                        json.optString(
                            "error_description",
                            "A GitHub bejelentkezés nem indítható."
                        )
                    )
                }
                DeviceCode(
                    deviceCode = json.getString("device_code"),
                    userCode = json.getString("user_code"),
                    verificationUri = json.optString(
                        "verification_uri",
                        "https://github.com/login/device"
                    ),
                    expiresIn = json.getLong("expires_in"),
                    intervalSeconds = json.optLong("interval", 5L),
                )
            }
        }
    }

    suspend fun pollDeviceCode(code: DeviceCode): Result<AuthResult> =
        withContext(Dispatchers.IO) {
            runCatching {
                var waitSeconds = code.intervalSeconds.coerceAtLeast(5L)
                val deadline = System.currentTimeMillis() + code.expiresIn * 1000L

                while (System.currentTimeMillis() < deadline) {
                    delay(waitSeconds * 1000L)

                    val body = FormBody.Builder()
                        .add("client_id", clientId)
                        .add("device_code", code.deviceCode)
                        .add(
                            "grant_type",
                            "urn:ietf:params:oauth:grant-type:device_code"
                        )
                        .build()

                    val request = Request.Builder()
                        .url(tokenUrl)
                        .header("Accept", "application/json")
                        .post(body)
                        .build()

                    http.newCall(request).execute().use { response ->
                        val raw = response.body?.string().orEmpty()
                        val json = JSONObject(raw)
                        val error = json.optString("error", "")

                        when (error) {
                            "" -> {
                                return@withContext Result.success(
                                    AuthResult(
                                        accessToken = json.getString("access_token"),
                                        refreshToken = json.optString("refresh_token", ""),
                                        expiresInSeconds = json.optLong("expires_in", 28800L),
                                    )
                                )
                            }
                            "authorization_pending" -> Unit
                            "slow_down" -> waitSeconds += 5L
                            "access_denied" ->
                                throw IOException("A GitHub-hozzáférést elutasítottad.")
                            "expired_token" ->
                                throw IOException(
                                    "A bejelentkezési kód lejárt. Indíts új bejelentkezést."
                                )
                            else ->
                                throw IOException(
                                    json.optString(
                                        "error_description",
                                        "GitHub bejelentkezési hiba."
                                    )
                                )
                        }
                    }
                }

                throw IOException("A GitHub bejelentkezési kód lejárt.")
            }
        }

    suspend fun refreshIfNeeded(): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val current = store.accessToken
            if (
                current.isNotBlank() &&
                System.currentTimeMillis() < store.expiresAtMs - 60_000L
            ) {
                return@withContext Result.success(current)
            }

            val refresh = store.refreshToken
            if (refresh.isBlank()) {
                if (current.isBlank()) throw IOException("Nincs GitHub-bejelentkezés.")
                return@withContext Result.success(current)
            }

            val body = FormBody.Builder()
                .add("client_id", clientId)
                .add("grant_type", "refresh_token")
                .add("refresh_token", refresh)
                .build()

            val request = Request.Builder()
                .url(tokenUrl)
                .header("Accept", "application/json")
                .post(body)
                .build()

            http.newCall(request).execute().use { response ->
                val raw = response.body?.string().orEmpty()
                val json = JSONObject(raw)
                if (!response.isSuccessful || json.has("error")) {
                    throw IOException(
                        json.optString(
                            "error_description",
                            "A GitHub munkamenet megújítása sikertelen."
                        )
                    )
                }

                val access = json.getString("access_token")
                store.accessToken = access
                json.optString("refresh_token")
                    .takeIf { it.isNotBlank() }
                    ?.let { store.refreshToken = it }
                store.expiresAtMs =
                    System.currentTimeMillis() +
                        json.optLong("expires_in", 28800L) * 1000L
                access
            }
        }
    }

    suspend fun completeLogin(code: DeviceCode): Result<String> {
        val auth = pollDeviceCode(code).getOrElse { return Result.failure(it) }
        store.accessToken = auth.accessToken
        if (auth.refreshToken.isNotBlank()) store.refreshToken = auth.refreshToken
        store.expiresAtMs =
            System.currentTimeMillis() + auth.expiresInSeconds * 1000L
        return Result.success(fetchLogin())
    }

    private suspend fun fetchLogin(): String = withContext(Dispatchers.IO) {
        val token = store.accessToken
        val request = Request.Builder()
            .url("https://api.github.com/user")
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .build()

        http.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IOException("A GitHub-fiók lekérése sikertelen.")
            }
            val login = JSONObject(raw).optString("login")
            if (login.isBlank()) {
                throw IOException("A GitHub-fiók neve nem érkezett meg.")
            }
            store.login = login
            login
        }
    }
}
