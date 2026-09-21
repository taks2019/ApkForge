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
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import java.io.File

enum class Phase { Idle, Dispatching, Queued, Running, Downloading, Done, Failed }

data class BuildUi(
    val phase: Phase = Phase.Idle,
    val message: String = "",
    val runUrl: String? = null,
    val apk: File? = null,
) {
    val active get() = phase in listOf(
        Phase.Dispatching,
        Phase.Queued,
        Phase.Running,
        Phase.Downloading
    )
}

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val store = Store(app)
    private val secure = SecureStore(app)
    val auth = GitHubAuth(app, secure)
    private val jobs = mutableMapOf<String, Job>()

    var loggedInAs by mutableStateOf(secure.login)
        private set

    val projects = mutableStateListOf<Project>().apply { addAll(store.loadProjects()) }
    val states = mutableStateMapOf<String, BuildUi>()
    var message by mutableStateOf<String?>(null)

    private fun gh() = GitHub(auth, getApplication<Application>().cacheDir)
    private fun set(p: Project, ui: BuildUi) { states[p.id] = ui }

    fun login() {
        if (!auth.isConfigured()) {
            message = "A GitHub App Client ID még nincs beállítva az APK Forge buildjében."
            return
        }

        viewModelScope.launch {
            val code = auth.requestDeviceCode().getOrElse {
                message = it.message ?: "Nem sikerült elindítani a GitHub bejelentkezést."
                return@launch
            }

            message =
                "GitHub-kód: ${code.userCode}. A megnyitott GitHub oldalon hagyd jóvá a hozzáférést."
            auth.openVerificationPage()

            auth.completeLogin(code)
                .onSuccess {
                    loggedInAs = it
                    message = "Sikeres GitHub-bejelentkezés: @$it"
                }
                .onFailure {
                    message = it.message ?: "A GitHub-bejelentkezés sikertelen."
                }
        }
    }

    fun logout() {
        auth.clear()
        loggedInAs = ""
        message = "GitHub kijelentkezve."
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
            message = "Add meg így: tulajdonos/repo"
            return
        }
        if (loggedInAs.isBlank()) {
            message = "Előbb jelentkezz be GitHubba."
            return
        }

        val (owner, repo) = parts
        if (projects.any { it.id.equals("$owner/$repo", true) }) {
            message = "Ez a repó már a listán van."
            return
        }

        viewModelScope.launch {
            gh().defaultBranch(owner, repo)
                .onSuccess { branch ->
                    projects.add(
                        Project(
                            owner,
                            repo,
                            branch,
                            workflow.ifBlank { DEFAULT_WORKFLOW }
                        )
                    )
                    store.saveProjects(projects)
                }
                .onFailure { message = it.message }
        }
    }

    fun removeProject(p: Project) {
        jobs.remove(p.id)?.cancel()
        states.remove(p.id)
        projects.remove(p)
        store.saveProjects(projects)
    }

    fun createWorkflow(p: Project) {
        if (loggedInAs.isBlank()) {
            message = "Előbb jelentkezz be GitHubba."
            return
        }

        viewModelScope.launch {
            gh().createWorkflow(p)
                .onSuccess {
                    message = "A ${p.workflow} létrejött a(z) ${p.id} repóban."
                }
                .onFailure { message = it.message }
        }
    }

    fun cancel(p: Project) {
        jobs.remove(p.id)?.cancel()
        set(p, BuildUi(Phase.Idle))
    }

    fun build(p: Project) {
        if (loggedInAs.isBlank()) {
            message = "Előbb jelentkezz be GitHubba."
            return
        }

        jobs[p.id]?.cancel()
        jobs[p.id] = viewModelScope.launch {
            val g = gh()
            set(p, BuildUi(Phase.Dispatching, "Build indítása…"))

            val prevId = g.latestRun(p).getOrNull()?.id
            g.dispatch(p).onFailure {
                set(
                    p,
                    BuildUi(
                        Phase.Failed,
                        it.message ?: "Nem sikerült elindítani."
                    )
                )
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
                            set(
                                p,
                                BuildUi(
                                    Phase.Running,
                                    "Fordítás folyamatban…",
                                    r.url
                                )
                            )
                        else ->
                            set(
                                p,
                                BuildUi(
                                    Phase.Queued,
                                    "Sorban áll…",
                                    r.url
                                )
                            )
                    }
                }
                delay(5000)
            }

            if (current.conclusion != "success") {
                set(
                    p,
                    BuildUi(
                        Phase.Failed,
                        "A build sikertelen (${current.conclusion}). Nézd meg a naplót.",
                        current.url
                    )
                )
                return@launch
            }

            set(
                p,
                BuildUi(
                    Phase.Downloading,
                    "APK letöltése…",
                    current.url
                )
            )

            g.downloadApk(p, current.id)
                .onSuccess {
                    set(
                        p,
                        BuildUi(
                            Phase.Done,
                            "Kész: ${it.name}",
                            current.url,
                            it
                        )
                    )
                }
                .onFailure {
                    set(
                        p,
                        BuildUi(
                            Phase.Failed,
                            it.message ?: "Letöltési hiba",
                            current.url
                        )
                    )
                }
        }
    }
}
