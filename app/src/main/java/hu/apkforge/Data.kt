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

data class Project(
    val owner: String,
    val repo: String,
    val branch: String,
    val workflow: String,
) {
    val id get() = "$owner/$repo"
}

data class Run(
    val id: Long,
    val status: String,
    val conclusion: String?,
    val url: String,
)

class Store(ctx: Context) {
    private val sp = ctx.getSharedPreferences("apkforge", Context.MODE_PRIVATE)

    var token: String
        get() = sp.getString("token", "") ?: ""
        set(v) { sp.edit().putString("token", v).apply() }

    fun loadProjects(): List<Project> {
        val a = JSONArray(sp.getString("projects", "[]"))
        return (0 until a.length()).map {
            val o = a.getJSONObject(it)
            Project(
                o.getString("owner"),
                o.getString("repo"),
                o.getString("branch"),
                o.getString("workflow"),
            )
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
name: APK Forge - Android APK Build
on:
  workflow_dispatch:

permissions:
  contents: read

jobs:
  build:
    name: Build Android APK
    runs-on: ubuntu-latest
    steps:
      - name: Forráskód letöltése
        uses: actions/checkout@v4

      - name: Java 17 beállítása
        uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '17'

      - name: Gradle beállítása
        uses: gradle/actions/setup-gradle@v4

      - name: APK build
        shell: bash
        run: |
          set -e
          if [ -f gradlew ]; then
            chmod +x gradlew
            ./gradlew assembleDebug --no-daemon
          elif [ -f build.gradle ] || [ -f build.gradle.kts ]; then
            gradle assembleDebug --no-daemon
          else
            echo "HIBA: Nem található Android/Gradle projekt."
            exit 1
          fi

      - name: APK artifact feltöltése
        uses: actions/upload-artifact@v4
        with:
          name: apk
          path: "**/build/outputs/apk/**/*.apk"
          if-no-files-found: error
          retention-days: 7
""".trimIndent() + "\n"

class GitHub(private val token: String, private val cacheDir: File) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    private val api = "https://api.github.com"
    private val json = "application/json".toMediaType()

    private fun req(url: String) = Request.Builder()
        .url(url)
        .header("Authorization", "Bearer $token")
        .header("Accept", "application/vnd.github+json")
        .header("X-GitHub-Api-Version", "2026-03-10")

    private fun err(r: Response): String {
        val text = r.body?.string().orEmpty()
        val msg = runCatching { JSONObject(text).optString("message") }.getOrDefault("")
        return "GitHub hiba (${r.code}): ${msg.ifBlank { "ismeretlen hiba" }}"
    }

    private fun getJson(url: String): JSONObject =
        http.newCall(req(url).build()).execute().use {
            if (!it.isSuccessful) throw IOException(err(it))
            JSONObject(it.body!!.string())
        }

    suspend fun defaultBranch(owner: String, repo: String): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching { getJson("$api/repos/$owner/$repo").getString("default_branch") }
        }

    suspend fun createRepository(
        name: String,
        description: String,
        private: Boolean,
    ): Result<Project> = withContext(Dispatchers.IO) {
        runCatching {
            val body = JSONObject()
                .put("name", name)
                .put("description", description)
                .put("private", private)
                // Kell egy kezdő branch, hogy azonnal workflow-t tudjunk létrehozni.
                .put("auto_init", true)
                .toString()
                .toRequestBody(json)

            http.newCall(req("$api/user/repos").post(body).build()).execute().use { r ->
                if (!r.isSuccessful) throw IOException(err(r))
                val o = JSONObject(r.body!!.string())
                val owner = o.getJSONObject("owner").getString("login")
                val repo = o.getString("name")
                val branch = o.optString("default_branch").ifBlank { "main" }
                Project(owner, repo, branch, DEFAULT_WORKFLOW)
            }
        }
    }

    suspend fun deleteRepository(owner: String, repo: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                http.newCall(
                    req("$api/repos/$owner/$repo").delete().build()
                ).execute().use { r ->
                    if (r.code != 204) throw IOException(err(r))
                }
            }
        }

    suspend fun dispatch(p: Project): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val body = JSONObject().put("ref", p.branch).toString().toRequestBody(json)
            val r = req("$api/repos/${p.id}/actions/workflows/${p.workflow}/dispatches")
                .post(body).build()
            http.newCall(r).execute().use {
                if (it.code != 204) throw IOException(
                    if (it.code == 404)
                        "Nem található a ${p.workflow} workflow. Használd a „Workflow létrehozása” gombot."
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
                    it.getString("html_url"),
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
            val dir = File(cacheDir, "apks").apply {
                deleteRecursively()
                mkdirs()
            }

            var result: File? = null
            http.newCall(req(url).build()).execute().use { resp ->
                if (!resp.isSuccessful) throw IOException(err(resp))
                ZipInputStream(resp.body!!.byteStream()).use { zin ->
                    var e = zin.nextEntry
                    while (e != null && result == null) {
                        if (!e.isDirectory && e.name.endsWith(".apk", true)) {
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
            val exists = http.newCall(req("$url?ref=${p.branch}").build()).use { it.code == 200 }
            if (exists) throw IOException("A ${p.workflow} már létezik a repóban, nem írom felül.")

            val body = JSONObject()
                .put("message", "APK build workflow hozzáadása (APK Forge)")
                .put(
                    "content",
                    Base64.encodeToString(WORKFLOW_TEMPLATE.toByteArray(), Base64.NO_WRAP)
                )
                .put("branch", p.branch)
                .toString()
                .toRequestBody(json)

            http.newCall(req(url).put(body).build()).execute().use {
                if (!it.isSuccessful) throw IOException(err(it))
            }
        }
    }
}
