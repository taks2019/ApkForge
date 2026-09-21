package hu.apkforge

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

enum class Phase { Idle, Dispatching, Queued, Running, Downloading, Done, Failed }

data class BuildUi(
    val phase: Phase = Phase.Idle,
    val message: String = "",
    val runUrl: String? = null,
    val apk: File? = null,
) {
    val active get() = phase in listOf(
        Phase.Dispatching, Phase.Queued, Phase.Running, Phase.Downloading
    )
}

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val store = Store(app)
    private val jobs = mutableMapOf<String, Job>()

    var token by mutableStateOf(store.token)
        private set

    val projects = mutableStateListOf<Project>().apply {
        addAll(store.loadProjects())
    }

    val states = mutableStateMapOf<String, BuildUi>()
    var message by mutableStateOf<String?>(null)

    private fun gh() = GitHub(token, getApplication<Application>().cacheDir)
    private fun set(p: Project, ui: BuildUi) { states[p.id] = ui }

    fun saveToken(t: String) {
        token = t.trim()
        store.token = token
    }

    fun addProject(input: String, workflow: String) {
        val parts = input.trim()
            .removePrefix("https://")
            .removePrefix("http://")
            .removePrefix("github.com/")
            .removeSuffix("/")
            .removeSuffix(".git")
            .split("/")

        if (parts.size < 2 || parts[0].isBlank() || parts[1].isBlank()) {
            message = "Add meg így: tulajdonos/repó"
            return
        }
        if (token.isBlank()) {
            message = "Előbb add meg a GitHub tokent."
            return
        }

        val owner = parts[0]
        val repo = parts[1]
        if (projects.any { it.id.equals("$owner/$repo", true) }) {
            message = "Ez a repó már a listán van."
            return
        }

        viewModelScope.launch {
            gh().defaultBranch(owner, repo)
                .onSuccess { branch ->
                    projects.add(Project(owner, repo, branch, workflow.ifBlank { DEFAULT_WORKFLOW }))
                    store.saveProjects(projects)
                    message = "Repó hozzáadva: $owner/$repo"
                }
                .onFailure { message = it.message }
        }
    }

    fun createRepository(name: String, description: String, private: Boolean) {
        if (token.isBlank()) {
            message = "Előbb add meg a GitHub tokent."
            return
        }

        val cleanName = name.trim()
        if (!cleanName.matches(Regex("[A-Za-z0-9._-]{1,100}"))) {
            message = "A repónév csak betűt, számot, pontot, kötőjelet és aláhúzást tartalmazhat."
            return
        }

        if (projects.any { it.repo.equals(cleanName, true) }) {
            message = "Ez a repó már szerepel az ApkForge listáján."
            return
        }

        viewModelScope.launch {
            message = "GitHub repó létrehozása…"
            gh().createRepository(cleanName, description.trim(), private)
                .onSuccess { p ->
                    projects.add(p)
                    store.saveProjects(projects)
                    message = "Repó létrejött: ${p.id}. Workflow telepítése…"

                    gh().createWorkflow(p)
                        .onSuccess {
                            message = "Kész: ${p.id}. Az APK build workflow is létrejött."
                        }
                        .onFailure {
                            message = "A repó létrejött, de a workflow telepítése sikertelen: ${it.message}"
                        }
                }
                .onFailure { message = it.message }
        }
    }

    fun removeProject(p: Project) {
        jobs.remove(p.id)?.cancel()
        states.remove(p.id)
        projects.remove(p)
        store.saveProjects(projects)
        message = "${p.id} eltávolítva az ApkForge listájából."
    }

    fun deleteRepository(p: Project) {
        if (token.isBlank()) {
            message = "Előbb add meg a GitHub tokent."
            return
        }

        jobs.remove(p.id)?.cancel()
        viewModelScope.launch {
            message = "GitHub repó törlése…"
            gh().deleteRepository(p.owner, p.repo)
                .onSuccess {
                    states.remove(p.id)
                    projects.remove(p)
                    store.saveProjects(projects)
                    message = "GitHub repó törölve: ${p.id}"
                }
                .onFailure { message = it.message }
        }
    }

    fun createWorkflow(p: Project) {
        viewModelScope.launch {
            message = "Workflow létrehozása…"
            gh().createWorkflow(p)
                .onSuccess { message = "A ${p.workflow} létrejött a(z) ${p.id} repóban." }
                .onFailure { message = it.message }
        }
    }

    fun cancel(p: Project) {
        jobs.remove(p.id)?.cancel()
        set(p, BuildUi(Phase.Idle))
    }

    fun build(p: Project) {
        if (token.isBlank()) {
            message = "Előbb add meg a GitHub tokent."
            return
        }

        jobs[p.id]?.cancel()
        jobs[p.id] = viewModelScope.launch {
            val g = gh()
            set(p, BuildUi(Phase.Dispatching, "GitHub Actions build indítása…"))

            val prevId = g.latestRun(p).getOrNull()?.id
            g.dispatch(p).onFailure {
                set(p, BuildUi(Phase.Failed, it.message ?: "Nem sikerült elindítani."))
                return@launch
            }

            var run: Run? = null
            var tries = 0
            while (run == null && tries < 20) {
                delay(3000)
                val r = g.latestRun(p).getOrNull()
                if (r != null && r.id != prevId) run = r
                tries++
            }

            val found = run
            if (found == null) {
                set(
                    p,
                    BuildUi(
                        Phase.Failed,
                        "A build nem indult el időben. Nézd meg a GitHub Actions oldalt."
                    )
                )
                return@launch
            }

            var current = found
            while (true) {
                val r = g.latestRun(p).getOrNull()
                if (r != null && r.id == current.id) {
                    current = r
                    when (r.status) {
                        "completed" -> break
                        "in_progress" ->
                            set(p, BuildUi(Phase.Running, "GitHub Actions: fordítás folyamatban…", r.url))
                        else ->
                            set(p, BuildUi(Phase.Queued, "GitHub Actions: sorban áll…", r.url))
                    }
                }
                delay(5000)
            }

            if (current.conclusion != "success") {
                set(
                    p,
                    BuildUi(
                        Phase.Failed,
                        "A build sikertelen (${current.conclusion}). Nyisd meg a naplót.",
                        current.url
                    )
                )
                return@launch
            }

            set(p, BuildUi(Phase.Downloading, "APK artifact letöltése…", current.url))
            g.downloadApk(p, current.id)
                .onSuccess {
                    set(p, BuildUi(Phase.Done, "APK elkészült: ${it.name}", current.url, it))
                }
                .onFailure {
                    set(p, BuildUi(Phase.Failed, it.message ?: "Letöltési hiba", current.url))
                }
        }
    }
}
