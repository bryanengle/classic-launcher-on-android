package app.gamenative.ui.screen.wow

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.gamenative.ui.component.dialog.WowCard
import app.gamenative.ui.component.dialog.WowLauncherTheme
import app.gamenative.ui.component.dialog.WowTabRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.height
import app.gamenative.ui.component.dialog.WowUi
import app.gamenative.utils.Net
import app.gamenative.wow.addons.AddonDb
import app.gamenative.wow.addons.AddonFlavor
import app.gamenative.wow.addons.AddonListing
import app.gamenative.wow.addons.AddonManager
import app.gamenative.wow.addons.InstalledAddon
import app.gamenative.wow.addons.provider.GitHubProvider
import app.gamenative.wow.addons.provider.WowInterfaceProvider
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Owner of addon writes (install, update, remove): they run in this app-wide scope, so closing the addon page never cuts one in
 * half, and the launcher keeps Play disabled while one is running (Codex OPT13-04). Searches stay in the page's own scope and are
 * cancelled with it.
 */
internal object WowAddonJobs {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    var running by mutableStateOf(0)
        private set
    /** The running write as (English, Hungarian), shared by every page instance: a reopened page shows it too; null when idle. */
    var label by mutableStateOf<Pair<String, String>?>(null)
        private set
    fun begin(label: Pair<String, String>? = null) { running++; if (label != null) this.label = label }
    fun end() { running--; if (running == 0) label = null }
}

/**
 * Addon manager behind the launcher (swipe left from the home page). Sources: GitHub releases and WoWInterface, merged into one
 * list; only addons with a WoW Classic Era file are offered (the addon backend is from WoWForeverywhere, GPL-3.0).
 */
internal class WowAddonsController(gameRoot: File, private val scope: CoroutineScope) {
    enum class Mode { INSTALLED, BROWSE }

    val addonsDir = File(gameRoot, "${WowClientDownloader.FLAVOR_DIR}/Interface/AddOns")
    private val manager = AddonManager(
        addonsDir = addonsDir,
        db = AddonDb.forAddonsDir(addonsDir),
        providers = listOf(WowInterfaceProvider(), GitHubProvider()),
        http = Net.http,
        targetFlavor = AddonFlavor.CLASSIC,
    )

    var mode by mutableStateOf(Mode.INSTALLED); private set
    var query by mutableStateOf(""); private set
    var listings by mutableStateOf<List<AddonListing>>(emptyList()); private set
    var installed by mutableStateOf<List<AddonListing>?>(null); private set
    var loading by mutableStateOf(false); private set
    /** The last browse reached none of the sources: the empty list is a network failure, not "nothing found". */
    var unreachable by mutableStateOf(false); private set
    /** The running write as (English, Hungarian); null when idle. It may belong to a page that was closed meanwhile. */
    val working: Pair<String, String>? get() = WowAddonJobs.label
    /** A write runs (from this page or from an earlier, closed one): every write action waits for it. */
    val busy: Boolean get() = WowAddonJobs.running > 0
    var message by mutableStateOf<Pair<String, String>?>(null)
    val updates get() = installed?.count { it.updateAvailable } ?: 0
    private var loadJob: Job? = null

    fun refreshInstalled() {
        scope.launch {
            installed = runCatching { withContext(Dispatchers.IO) { manager.installed() } }.getOrDefault(emptyList())
            if (mode == Mode.INSTALLED) showInstalled()
        }
    }

    fun switchMode(newMode: Mode) {
        mode = newMode
        if (newMode == Mode.INSTALLED) showInstalled() else load()
    }

    fun search(text: String) {
        query = text
        if (mode == Mode.INSTALLED) showInstalled() else load(debounce = true)
    }

    private fun showInstalled() {
        val q = query.trim().lowercase()
        listings = installed.orEmpty().filter { q.isEmpty() || q in it.remote.name.lowercase() }
    }

    private fun load(debounce: Boolean = false) {
        loadJob?.cancel()
        loadJob = scope.launch {
            if (debounce) delay(400)
            loading = true
            try {
                val found = withContext(Dispatchers.IO) {
                    if (query.isBlank()) manager.popularOutcome(includeClassic = true) else manager.searchOutcome(query, includeClassic = true)
                }
                unreachable = found.noSourceReachable
                // Only what WoW Classic Era loads: a file made for Forever (Classic-only and unknown builds are left out).
                listings = found.listings.filter { it.bestFlavor == AddonFlavor.CLASSIC }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                unreachable = true
                listings = emptyList()
            } finally {
                loading = false
            }
        }
    }

    fun install(l: AddonListing) = act("Installing ${l.remote.name}" to "${l.remote.name} telepítése") { manager.install(l.remote) }
    fun update(l: AddonListing) = act("Updating ${l.remote.name}" to "${l.remote.name} frissítése") {
        val record = l.installed ?: return@act
        manager.update(record)
    }
    fun remove(record: InstalledAddon) = act("Removing ${record.name}" to "${record.name} eltávolítása") { manager.remove(record) }
    fun updateAll() = act("Updating all addons" to "Minden addon frissítése") { manager.updateAll() }
    fun installFromUrl(url: String) = act("Installing from link" to "Telepítés linkről") { manager.installFromUrl(url.trim()) }

    private fun act(label: Pair<String, String>, block: suspend () -> Unit) {
        // One write at a time for the whole app: each page instance has its own AddonManager (and lock), so the shared count is the gate.
        if (busy || app.gamenative.PluviaApp.keepAlive) return
        message = null
        WowAddonJobs.begin(label)
        WowAddonJobs.scope.launch {
            try {
                withContext(Dispatchers.IO) { block() }
                message = "${label.first}: done." to "${label.second}: kész."
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val reason = e.message ?: "unknown error"
                message = "${label.first} failed: $reason" to "${label.second} nem sikerült: $reason"
            } finally {
                WowAddonJobs.end()
                installed = runCatching { withContext(Dispatchers.IO) { manager.installed() } }.getOrDefault(installed.orEmpty())
                if (mode == Mode.INSTALLED) showInstalled() else listings = listings.map { refreshed(it) }
            }
        }
    }

    private fun refreshed(l: AddonListing): AddonListing {
        val record = installed?.firstOrNull { it.remote.provider == l.remote.provider && it.remote.projectId == l.remote.projectId }
        return l.copy(installed = record?.installed, updateAvailable = record?.updateAvailable ?: false)
    }
}

/** The addon page: installed addons with updates, a search over the sources, and install from a link. */
@Composable
internal fun WowAddonsPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val gamePath = remember { GamePath.load(context) }
    val ready = remember { GamePath.isReady(context, gamePath) }
    val c = remember(gamePath) { WowAddonsController(File(gamePath), scope) }
    // Also after a write that an earlier, closed page started has finished.
    val idle = WowAddonJobs.running == 0
    LaunchedEffect(c, idle) { if (ready && idle) c.refreshInstalled() }
    var linkDialog by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf<InstalledAddon?>(null) }

    WowLauncherTheme {
        // The same frame as Settings: 15 % smaller than the system scale, serif gold title, equal tabs, Close at the bottom right.
        val systemDensity = androidx.compose.ui.platform.LocalDensity.current
        androidx.compose.runtime.CompositionLocalProvider(
            androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(systemDensity.density * 0.85f, systemDensity.fontScale),
        ) {
        Box(Modifier.fillMaxSize().background(WowUi.background).windowInsetsPadding(WindowInsets.safeDrawing).padding(20.dp)) {
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Column {
                    Text(tx("ADDONS", "ADDONOK"), fontSize = 22.sp, fontWeight = FontWeight.Black,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Serif, letterSpacing = 5.sp, color = WowUi.Gold)
                    Text(tx("Only addons made for WoW Classic Era.", "Csak a WoW Classic Era-hez készült addonok."),
                        color = WowUi.Muted, fontSize = 12.sp)
                }
                if (!ready) {
                    WowCard { Text(tx("Install the game first: the addons go into its Interface/AddOns folder.",
                        "Előbb telepítsd a játékot: az addonok az Interface/AddOns mappájába kerülnek.")) }
                    Spacer(Modifier.weight(1f))
                    AddonsCloseButton(onBack)
                    return@Column
                }
                val updates = c.updates
                val modes = listOf(WowAddonsController.Mode.INSTALLED, WowAddonsController.Mode.BROWSE)
                WowTabRow(listOf(tx("Installed", "Telepítettek") + if (updates > 0) " ($updates ↑)" else "", tx("Browse", "Böngészés")),
                    modes.indexOf(c.mode), enabled = true, maxSize = 15.sp) { c.switchMode(modes[it]) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(value = c.query, onValueChange = c::search, singleLine = true, modifier = Modifier.weight(1f),
                        placeholder = { Text(tx("Search addons", "Addon keresése")) })
                    if (c.mode == WowAddonsController.Mode.INSTALLED && updates > 0) {
                        Button(onClick = c::updateAll, enabled = !c.busy, shape = RoundedCornerShape(14.dp)) { Text(tx("Update all", "Mindet frissít")) }
                    }
                    OutlinedButton(onClick = { linkDialog = true }, enabled = !c.busy, shape = RoundedCornerShape(14.dp)) { Text(tx("From link", "Linkről")) }
                }
                c.working?.let { (en, hu) ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp); Text(tx(en, hu), color = WowUi.Gold)
                    }
                }
                c.message?.let { (en, hu) -> Text(tx(en, hu), color = if ("failed" in en || "Couldn't" in en) WowUi.Error else WowUi.Muted, fontSize = 12.sp) }
                when {
                    c.loading -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    c.listings.isEmpty() && c.mode == WowAddonsController.Mode.BROWSE && c.unreachable -> Text(
                        tx("Couldn't reach the addon sites. Check your connection.", "Nem érhetők el az addon-oldalak. Ellenőrizd a kapcsolatot."),
                        color = WowUi.Error)
                    c.listings.isEmpty() -> Text(
                        if (c.mode == WowAddonsController.Mode.INSTALLED) tx("No addons installed yet. Open Browse to find some.",
                            "Még nincs telepített addon. A Böngészés fülön kereshetsz.")
                        else tx("Nothing found for WoW Classic Era.", "Nincs találat a WoW Classic Era-hez."),
                        color = WowUi.Muted)
                    else -> LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(c.listings, key = { "${it.remote.provider}:${it.remote.projectId}:${it.installed?.id}" }) { l ->
                            AddonRow(l, busy = c.busy, onInstall = { c.install(l) }, onUpdate = { c.update(l) },
                                onRemove = { l.installed?.let { removing = it } })
                        }
                    }
                }
                if (c.loading || c.listings.isEmpty()) Spacer(Modifier.weight(1f))
                AddonsCloseButton(onBack)
            }
        }
        }
        if (linkDialog) {
            var url by remember { mutableStateOf("") }
            AlertDialog(onDismissRequest = { linkDialog = false },
                title = { Text(tx("Install from link", "Telepítés linkről")) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(tx("A GitHub repository (github.com/owner/repo) or a direct .zip link.",
                            "GitHub-repó (github.com/szerző/repó) vagy közvetlen .zip link."), fontSize = 12.sp)
                        OutlinedTextField(value = url, onValueChange = { url = it }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    }
                },
                confirmButton = { TextButton(enabled = url.isNotBlank() && !c.busy, onClick = { linkDialog = false; c.installFromUrl(url) }) { Text(tx("Install", "Telepítés")) } },
                dismissButton = { TextButton(onClick = { linkDialog = false }) { Text(tx("Cancel", "Mégse")) } })
        }
        removing?.let { record ->
            AlertDialog(onDismissRequest = { removing = null },
                title = { Text(tx("Remove ${record.name}?", "Eltávolítod: ${record.name}?")) },
                text = { Text(tx("Its folders are deleted from Interface/AddOns: ", "Ezek a mappák törlődnek az Interface/AddOns-ból: ") +
                    record.folders.joinToString(", ")) },
                confirmButton = { TextButton(enabled = !c.busy, onClick = { removing = null; c.remove(record) }) { Text(tx("Remove", "Eltávolítás")) } },
                dismissButton = { TextButton(onClick = { removing = null }) { Text(tx("Cancel", "Mégse")) } })
        }
    }
}

/** Close at the bottom right, the same button as in Settings; back to the launcher. */
@Composable
private fun AddonsCloseButton(onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        OutlinedButton(onClick = onBack, shape = RoundedCornerShape(14.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, androidx.compose.ui.graphics.Color(0xFFA0AEC0).copy(alpha = 0.6f)),
            modifier = Modifier.height(48.dp)) {
            Text(tx("Close", "Bezárás"), color = WowUi.Gold, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun AddonRow(l: AddonListing, busy: Boolean, onInstall: () -> Unit, onUpdate: () -> Unit, onRemove: () -> Unit) {
    WowCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(36.dp).background(WowUi.GoldFill, CircleShape), contentAlignment = Alignment.Center) {
                Text(l.remote.name.trim().take(1).uppercase(), color = WowUi.Cream, fontWeight = FontWeight.Black)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(l.remote.name.trim(), color = WowUi.Cream, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val version = l.installed?.version ?: l.remote.latest[AddonFlavor.CLASSIC]?.version
                Text(listOfNotNull(l.remote.author.takeIf { it.isNotBlank() }, version).joinToString(" · "),
                    color = WowUi.Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (l.remote.summary.isNotBlank()) Text(l.remote.summary, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(8.dp))
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                when {
                    l.installed == null -> Button(onClick = onInstall, enabled = !busy) { Text(tx("Install", "Telepítés")) }
                    else -> {
                        if (l.updateAvailable) Button(onClick = onUpdate, enabled = !busy) { Text(tx("Update", "Frissítés")) }
                        OutlinedButton(onClick = onRemove, enabled = !busy) { Text(tx("Remove", "Eltávolítás")) }
                    }
                }
            }
        }
    }
}
