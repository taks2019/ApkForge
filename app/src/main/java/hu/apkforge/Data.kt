package hu.apkforge

import android.content.Context
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

data class Project(val owner: String, val repo: String, val branch: String, val workflow: String) {
    val id get() = "$owner/$repo"
}

data class Run(val id: Long, val status: String, val conclusion: String?, val url: String)

class Store(ctx: Context) {
    private val sp = ctx.getSharedPreferences("apkforge", Context.MODE_PRIVATE)

    fun loadProjects(): List<Project> {
        val a = JSONArray(sp.getString("projects", "[]"))
        return (0 until a.length()).map {
            val o = a.getJSONObject(it)
            Project(o.getString("owner"), o.getString("repo"), o.getString("branch"), o.getString("workflow"))
        }
    }

    fun saveProjects(list: List<Project>) {
        val a = JSONArray()
        list.forEach {
            a.put(
                JSONObject()
                    .put("owner", it.owner)
                    .put("repo", it.repo)
                    .put("branch", it.branch)
                    .put("workflow", it.workflow)
            )
        }
        sp.edit().putString("projects", a.toString()).apply()
    }
}

const val DEFAULT_WORKFLOW = "build-apk.yml"

private val WORKFLOW_TEMPLATE = """
name: Build APK
on:
  workflow_dispatch:
jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: 17
      - uses: gradle/actions/setup-gradle@v4
        with:
          gradle-version: 8.9
      - name: Build debug APK
        run: |
          if [ -f gradlew ]; then chmod +x gradlew; ./gradlew assembleDebug --no-daemon; else gradle assembleDebug --no-daemon; fi
      - uses: actions/upload-artifact@v4
        with:
          name: apk
          path: "**/build/outputs/apk/**/*.apk"
""".trimIndent() + "\n"

class GitHub(
    private val auth: GitHubAuth,
    private val cacheDir: File
) {
    private val http = OkHttpClient.Builder().readTimeout(90, TimeUnit.SECONDS).build()
    private val api = "https://api.github.com"
    private val json = "application/json".toMediaType()

    private suspend fun req(url: String): Request.Builder {
        val token = auth.refreshIfNeeded().getOrThrow()
        return Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
    }

    private fun err(r: Response): String {
        val text = r.body?.string().orEmpty()
        val msg = runCatching { JSONObject(text).optString("message") }.getOrDefault("")
        return "GitHub hiba (${r.code}): ${msg.ifBlank { "ismeretlen hiba" }}"
    }

    private suspend fun getJson(url: String): JSONObject = withContext(Dispatchers.IO) {
        req(url).build().let { request ->
            http.newCall(request).execute().use {
                if (!it.isSuccessful) throw IOException(err(it))
                JSONObject(it.body!!.string())
            }
        }
    }

    suspend fun defaultBranch(owner: String, repo: String): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching { getJson("$api/repos/$owner/$repo").getString("default_branch") }
        }

    suspend fun dispatch(p: Project): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val body = JSONObject().put("ref", p.branch).toString().toRequestBody(json)
            val request = req("$api/repos/${p.id}/actions/workflows/${p.workflow}/dispatches")
                .post(body).build()
            http.newCall(request).execute().use {
                if (it.code != 204) throw IOException(
                    if (it.code == 404) "Nem található a ${p.workflow} workflow. Használd a „Workflow létrehozása” gombot."
                    else err(it)
                )
            }
        }
    }

    suspend fun latestRun(p: Project): Result<Run?> = withContext(Dispatchers.IO) {
        runCatching {
            val arr = getJson(
                "$api/repos/${p.id}/actions/workflows/${p.workflow}/runs?branch=${p.branch}&per_page=1"
            ).getJSONArray("workflow_runs")
            if (arr.length() == 0) null else arr.getJSONObject(0).let {
                Run(
                    it.getLong("id"),
                    it.getString("status"),
                    if (it.isNull("conclusion")) null else it.getString("conclusion"),
                    it.getString("html_url")
                )
            }
        }
    }

    suspend fun downloadApk(p: Project, runId: Long): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val arts = getJson("$api/repos/${p.id}/actions/runs/$runId/artifacts")
                .getJSONArray("artifacts")
            if (arts.length() == 0) throw IOException("A build nem hagyott maga után artifactot.")
            val url = arts.getJSONObject(0).getString("archive_download_url")
            val dir = File(cacheDir, "apks").apply { deleteRecursively(); mkdirs() }
            var result: File? = null
            val request = req(url).build()
            http.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) throw IOException(err(resp))
                ZipInputStream(resp.body!!.byteStream()).use { zin ->
                    var e = zin.nextEntry
                    while (e != null && result == null) {
                        if (!e.isDirectory && e.name.endsWith(".apk")) {
                            val out = File(dir, File(e.name).name)
                            out.outputStream().use { zin.copyTo(it) }
                            result = out
                        }
                        e = zin.nextEntry
                    }
                }
            }
            result ?: throw IOException("Nincs APK a letöltött csomagban.")
        }
    }

    suspend fun createWorkflow(p: Project): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val url = "$api/repos/${p.id}/contents/.github/workflows/${p.workflow}"
            val exists = req("$url?ref=${p.branch}").build().let { request ->
                http.newCall(request).execute().use { it.code == 200 }
            }
            if (exists) throw IOException("A ${p.workflow} már létezik a repóban, nem írom felül.")

            val body = JSONObject()
                .put("message", "APK build workflow hozzáadása (APK Forge)")
                .put("content", Base64.encodeToString(WORKFLOW_TEMPLATE.toByteArray(), Base64.NO_WRAP))
                .put("branch", p.branch)
                .toString().toRequestBody(json)

            val request = req(url).put(body).build()
            http.newCall(request).execute().use {
                if (!it.isSuccessful) throw IOException(err(it))
            }
        }
    }
}
