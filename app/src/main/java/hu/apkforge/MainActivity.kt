package hu.apkforge

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import android.os.Bundle
import java.io.File

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { ApkForgeTheme { App() } }
    }
}

private fun installApk(ctx: Context, file: File) {
    if (!ctx.packageManager.canRequestPackageInstalls()) {
        ctx.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        return
    }
    val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
    ctx.startActivity(
        Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    )
}

private fun openUrl(ctx: Context, url: String) =
    ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App(vm: MainViewModel = viewModel()) {
    var showAdd by remember { mutableStateOf(false) }
    var showToken by remember { mutableStateOf(vm.token.isBlank()) }
    val snack = remember { SnackbarHostState() }

    LaunchedEffect(vm.message) {
        vm.message?.let { snack.showSnackbar(it); vm.message = null }
    }

    Scaffold(
        topBar = {
            LargeTopAppBar(
                title = { Text("APK Forge", fontWeight = FontWeight.SemiBold) },
                actions = {
                    IconButton(onClick = { showToken = true }) {
                        Icon(Icons.Default.Key, contentDescription = "GitHub token")
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showAdd = true },
                icon = { Icon(Icons.Default.Add, null) },
                text = { Text("Repó hozzáadása") },
            )
        },
        snackbarHost = { SnackbarHost(snack) },
    ) { pad ->
        if (vm.projects.isEmpty()) {
            Box(Modifier.padding(pad).fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.Construction, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
                    Text("Még nincs egy repód sem", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Add hozzá a GitHub repót, amiből APK-t szeretnél. A fordítást a GitHub végzi, az APK pedig ide érkezik.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            LazyColumn(
                Modifier.padding(pad).fillMaxSize(),
                contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(vm.projects, key = { it.id }) { p ->
                    ProjectCard(p, vm.states[p.id] ?: BuildUi(), vm)
                }
            }
        }
    }

    if (showAdd) AddDialog(onDismiss = { showAdd = false }) { repo, wf -> vm.addProject(repo, wf); showAdd = false }
    if (showToken) TokenDialog(vm.token, onDismiss = { showToken = false }) { vm.saveToken(it); showToken = false }
}

@Composable
fun ProjectCard(p: Project, ui: BuildUi, vm: MainViewModel) {
    val ctx = LocalContext.current
    var menu by remember { mutableStateOf(false) }

    ElevatedCard(colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(p.repo, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        "${p.owner} · ${p.branch} · ${p.workflow}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "Több") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text("Workflow létrehozása") },
                            leadingIcon = { Icon(Icons.Default.PostAdd, null) },
                            onClick = { menu = false; vm.createWorkflow(p) },
                        )
                        DropdownMenuItem(
                            text = { Text("Megnyitás GitHubon") },
                            leadingIcon = { Icon(Icons.Default.OpenInBrowser, null) },
                            onClick = { menu = false; openUrl(ctx, "https://github.com/${p.id}") },
                        )
                        DropdownMenuItem(
                            text = { Text("Eltávolítás a listáról") },
                            leadingIcon = { Icon(Icons.Default.Delete, null) },
                            onClick = { menu = false; vm.removeProject(p) },
                        )
                    }
                }
            }

            if (ui.phase != Phase.Idle) StatusBlock(ui)

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                when {
                    ui.active -> OutlinedButton(onClick = { vm.cancel(p) }) {
                        Icon(Icons.Default.Close, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Leállítás")
                    }
                    else -> Button(onClick = { vm.build(p) }) {
                        Icon(Icons.Default.Build, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
                        Text(if (ui.phase == Phase.Idle) "APK építése" else "Építés újra")
                    }
                }
                if (ui.phase == Phase.Done && ui.apk != null) {
                    FilledTonalButton(onClick = { installApk(ctx, ui.apk) }) {
                        Icon(Icons.Default.InstallMobile, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Telepítés")
                    }
                }
                if (ui.runUrl != null) {
                    TextButton(onClick = { openUrl(ctx, ui.runUrl) }) { Text("Napló") }
                }
            }
        }
    }
}

@Composable
private fun StatusBlock(ui: BuildUi) {
    val (icon, tint) = when (ui.phase) {
        Phase.Done -> Icons.Default.CheckCircle to MaterialTheme.colorScheme.primary
        Phase.Failed -> Icons.Default.Error to MaterialTheme.colorScheme.error
        else -> Icons.Default.Sync to MaterialTheme.colorScheme.tertiary
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(icon, null, Modifier.size(20.dp), tint = tint)
            Text(ui.message, style = MaterialTheme.typography.bodyMedium)
        }
        if (ui.active) LinearProgressIndicator(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.tertiary)
    }
}

@Composable
fun AddDialog(onDismiss: () -> Unit, onAdd: (String, String) -> Unit) {
    var repo by remember { mutableStateOf("") }
    var wf by remember { mutableStateOf(DEFAULT_WORKFLOW) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Repó hozzáadása") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    repo, { repo = it }, label = { Text("tulajdonos/repó vagy GitHub link") },
                    singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
                OutlinedTextField(wf, { wf = it }, label = { Text("Workflow fájl") }, singleLine = true)
            }
        },
        confirmButton = { TextButton(onClick = { onAdd(repo, wf) }, enabled = repo.isNotBlank()) { Text("Hozzáadás") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Mégse") } },
    )
}

@Composable
fun TokenDialog(current: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var t by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("GitHub token") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "GitHub → Settings → Developer settings → Personal access tokens. " +
                        "Classic tokennél kell a repo és a workflow jog; fine-grained tokennél az Actions és a Contents írási joga.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    t, { t = it }, label = { Text("Token") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSave(t) }) { Text("Mentés") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Mégse") } },
    )
}
