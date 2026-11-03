package app.gamenative.ui.screen.wow

import app.gamenative.ui.theme.WowTheme

import android.content.Context
import android.os.Build
import android.os.Environment
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import app.gamenative.ui.component.dialog.WowSettingsDialog
import androidx.compose.runtime.CompositionLocalProvider
import app.gamenative.utils.StorageUtils
import com.winlator.container.Container
import com.winlator.container.ContainerManager
import com.winlator.xenvironment.ImageFs
import app.gamenative.PluviaApp
import app.gamenative.events.AndroidEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

object WoWLauncherState {
    var shouldAutoLaunch = true
    private const val PREFS = "wow_forever"
    private const val KEY_AUTO_LAUNCH = "auto_launch"
    /** Settings > Quick "When the app opens": start the game at once (default) or show the home page. */
    fun autoLaunchEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_AUTO_LAUNCH, true)
    fun setAutoLaunch(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_AUTO_LAUNCH, enabled).apply()
    }
}

@Composable
fun WoWForeverScreen(
    onLaunch: (appId: String) -> Unit,
) {
    val context = LocalContext.current
    // In-app language (System / Magyar / English): app-wide state, provided at the root (PluviaMain) for every screen.
    val language = WowUiLanguage.current
    CompositionLocalProvider(LocalWowHungarian provides WowUiLanguage.isHungarian(language), LocalWowLanguage provides WowUiLanguage.code(language)) {
        // The addon manager opens over the launcher (Addons button) and closes with its Home button or Back. The launcher stays
        // composed underneath, so its launch/version effects never restart.
        var showAddons by remember { mutableStateOf(false) }
        androidx.activity.compose.BackHandler(enabled = showAddons) { showAddons = false }
        Box(Modifier.fillMaxSize()) {
            WoWForeverScreenContent(onLaunch, language, { WowUiLanguage.save(context, it) }, onOpenAddons = { showAddons = true })
            if (showAddons) Box(Modifier.fillMaxSize().pointerInput(Unit) {
                // Swallow every touch the page itself does not use: the launcher's buttons underneath must not react.
                awaitPointerEventScope { while (true) awaitPointerEvent().changes.forEach { it.consume() } }
            }) {
                WowAddonsPage(onBack = { showAddons = false })
            }
        }
    }
}

@Composable
private fun WoWForeverScreenContent(
    onLaunch: (appId: String) -> Unit,
    language: WowLanguage,
    onLanguageChange: (WowLanguage) -> Unit,
    onOpenAddons: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val t = rememberTx()

    var gamePath by remember { mutableStateOf(GamePath.load(context)) }

    var isPreparingBundledComponents by remember { mutableStateOf(true) }
    var isLaunching by remember { mutableStateOf(false) }
    var launchJob by remember { mutableStateOf<Job?>(null) }
    var hasSavedLogin by remember { mutableStateOf(BattleNetSignIn.load(context) != null) }
    var statusText by remember { mutableStateOf(t("Ready to launch", "Indításra kész")) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var exeExists by remember { mutableStateOf(false) }
    var dataExists by remember { mutableStateOf(false) }
    var buildInfoExists by remember { mutableStateOf(false) }
    var buildInfoValid by remember { mutableStateOf(false) }
    var hasStorageAccess by remember { mutableStateOf(true) }
    var showStoragePermissionDialog by remember { mutableStateOf(false) }
    // How much of the streamed game data is on the phone (WowGameData); a launch with very little asks once first.
    var gameData by remember { mutableStateOf<WowGameData.Progress?>(null) }
    var showLowDataDialog by remember { mutableStateOf(false) }
    var lowDataAcknowledged by remember { mutableStateOf(false) }
    var gameDataRevision by remember { mutableStateOf(0) }
    var versionStatus by remember { mutableStateOf<WowClientDownloader.VersionCheckResult?>(null) }
    var isCheckingVersion by remember { mutableStateOf(false) }
    var isUpdating by remember { mutableStateOf(false) }
    var updateStatusText by remember { mutableStateOf("") }
    var isWrongGame by remember { mutableStateOf(false) }
    // A Classic beta install (its .build.info has no full-release row yet): Play waits for the in-place upgrade.
    var showInstallDialog by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var nextLaunch by remember { mutableStateOf<WowQuickSettings.Status?>(null) }
    // What the next Play will use; refreshed when the launcher opens and whenever Settings closes.
    LaunchedEffect(showSettings, gamePath) {
        if (!showSettings) nextLaunch = withContext(Dispatchers.IO) {
            runCatching {
                com.winlator.container.ContainerManager(context).getContainerById(WowContainerProfile.CONTAINER_ID)
                    ?.let { WowQuickSettings.status(context, it) }
            }.getOrNull()
        }
    }

    // One version request per game folder at a time: changing the folder resumes the launcher AND asks for a check itself, which used
    // to send the same two network requests twice. Only the explicit Refresh button replaces a running check.
    val versionCheck = remember { object { var job: Job? = null; var path: String? = null } }

    fun checkVersionStatus(force: Boolean = false) {
        val requestedPath = gamePath
        if (!force && versionCheck.path == requestedPath && versionCheck.job?.isActive == true) return
        versionCheck.job?.cancel()
        versionCheck.path = requestedPath
        isCheckingVersion = true
        versionCheck.job = scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    if (File(requestedPath, ".build.info").exists()) WowClientDownloader.checkVersion(File(requestedPath)) else null
                }
                if (gamePath == requestedPath) versionStatus = result
            } finally { if (gamePath == requestedPath && versionCheck.path == requestedPath) isCheckingVersion = false }
        }
    }

    suspend fun checkFiles(): Boolean {
        val requestedPath = gamePath
        val snapshot = withContext(Dispatchers.IO) { WowGameStatus.scan(File(requestedPath)) }
        if (gamePath != requestedPath) return false
        exeExists = snapshot.executable
        dataExists = snapshot.data
        buildInfoExists = snapshot.buildInfo
        buildInfoValid = snapshot.buildInfoValid
        hasStorageAccess = StorageUtils.hasStoragePermission(context, requestedPath)
        isWrongGame = snapshot.wrongGame
        if (isWrongGame) {
            errorMessage = t("Incompatible game (${snapshot.product}). Classic Era Launcher requires World of Warcraft Classic Era (wow_classic_era).", "Nem kompatibilis játék (${snapshot.product}). A Classic Era Launcherhez World of Warcraft Classic Era (wow_classic_era) kell.")
        }
        return exeExists && dataExists && buildInfoExists && hasStorageAccess && !isWrongGame
    }

    LifecycleResumeEffect(gamePath) {
        val statusJob = scope.launch {
            checkFiles()
            if (versionStatus == null && !WoWLauncherState.shouldAutoLaunch) checkVersionStatus()
        }
        isLaunching = false
        statusText = t("Ready to launch", "Indításra kész")
        onPauseOrDispose {
            statusJob.cancel()
            launchJob?.cancel()
            launchJob = null
        }
    }

    DisposableEffect(Unit) {
        val reset: (Any) -> Unit = {
            launchJob?.cancel()
            launchJob = null
            isLaunching = false
            statusText = t("Ready to launch", "Indításra kész")
        }
        PluviaApp.events.on<AndroidEvent.ForceCloseApp, Unit>(reset)
        PluviaApp.events.on<AndroidEvent.GuestProgramTerminated, Unit>(reset)
        onDispose {
            PluviaApp.events.off<AndroidEvent.ForceCloseApp, Unit>(reset)
            PluviaApp.events.off<AndroidEvent.GuestProgramTerminated, Unit>(reset)
        }
    }

    // A folder picked before All files access was granted: checked again when the player comes back from the permission screen.
    var pendingPick by remember { mutableStateOf<String?>(null) }
    var pendingPickUri by remember { mutableStateOf<android.net.Uri?>(null) }
    // A folder Android does not let the app read although All files access is on: some phones apply the permission only to a
    // newly started process, so the launcher offers a restart (the folder is saved and checked again after it).
    var restartForAccess by remember { mutableStateOf<String?>(null) }

    /** Uses a picked folder: without All files access nothing under it is readable, so ask for that first instead of "not found". */
    suspend fun pickedFolder(path: String): File? {
        if (!StorageUtils.hasStoragePermission(context, path)) {
            pendingPick = path
            errorMessage = null
            hasStorageAccess = false
            showStoragePermissionDialog = true
            return null
        }
        pendingPick = null
        val (root, readable) = withContext(Dispatchers.IO) { GamePath.findGameRoot(File(path)) to (File(path).list() != null) }
        if (root == null) {
            if (!readable) {
                val info = withContext(Dispatchers.IO) { GamePath.accessReport(File(path)) }
                android.util.Log.w("ForeverLauncher", "Picked folder not readable: $info")
                errorMessage = t("Android does not let the app read $path although All files access is on ($info).",
                    "Az Android nem engedi olvasni ezt a mappát: $path, pedig a Teljes fájlhozzáférés be van kapcsolva ($info).")
                GamePath.save(context, path)
                restartForAccess = path
            } else errorMessage = t("No .build.info found in $path. Pick the folder that contains .build.info and Data/.", "Nincs .build.info itt: $path. Azt a mappát válaszd, amelyikben a .build.info és a Data/ van.")
        }
        return root
    }

    suspend fun useGameRoot(root: File) {
        errorMessage = null
        gamePath = root.absolutePath
        GamePath.save(context, gamePath)
        if (!WowGameStorage.isPrivate(context, gamePath)) WowGameStorage.rememberShared(context, root)
        versionStatus = null
        val ok = checkFiles()
        if (!hasStorageAccess) showStoragePermissionDialog = true
        else if (ok) checkVersionStatus()
    }

    // Where a new install goes: an empty folder or one with the game is used as it is; any other gets a "WoW Classic Era" folder.
    var installTarget by remember { mutableStateOf<String?>(null) }
    val installFolderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        scope.launch {
            if (uri == null) return@launch
            val path = withContext(Dispatchers.IO) { StorageUtils.getPathFromTreeUri(context, uri) } ?: return@launch
            installTarget = withContext(Dispatchers.IO) {
                val dir = File(path)
                if (File(dir, ".build.info").isFile || dir.name.equals("WoW Classic Era", true) || dir.list().isNullOrEmpty()) dir.absolutePath
                else File(dir, "WoW Classic Era").absolutePath
            }
        }
    }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        scope.launch {
            if (uri == null) return@launch
            pendingPickUri = uri
            val path = withContext(Dispatchers.IO) { StorageUtils.getPathFromTreeUri(context, uri) } ?: return@launch
            useGameRoot(pickedFolder(path) ?: return@launch)
            pendingPickUri = null
        }
    }

    // Back from the All files access screen: use the folder that was picked before the permission was there.
    LifecycleResumeEffect(pendingPick) {
        val picked = pendingPick
        val job = if (picked != null && StorageUtils.hasStoragePermission(context, picked)) scope.launch {
            // Resolve the picked URI again now that the files are visible: the first answer may have been a guess.
            val path = pendingPickUri?.let { withContext(Dispatchers.IO) { StorageUtils.getPathFromTreeUri(context, it) } } ?: picked
            useGameRoot(pickedFolder(path) ?: return@launch)
            pendingPickUri = null
        } else null
        onPauseOrDispose { job?.cancel() }
    }

    var storageBusy by remember { mutableStateOf(false) }
    var storageMessage by remember { mutableStateOf<String?>(null) }
    var storageProgress by remember { mutableStateOf(0f) }
    // The screen must not time out in the middle of a download, an update, a storage move or a launch - but may when the launcher is idle.
    val launcherBusy = isPreparingBundledComponents || isLaunching || isUpdating || storageBusy
    val awakeOwner = remember { Any() }   // only this screen instance may clear its own request (a replacement may already have asked again)
    LaunchedEffect(launcherBusy) { app.gamenative.ui.util.ScreenAwake.set(context as? android.app.Activity, "launcher-busy", launcherBusy, awakeOwner) }
    DisposableEffect(Unit) { onDispose { app.gamenative.ui.util.ScreenAwake.set(context as? android.app.Activity, "launcher-busy", false, awakeOwner) } }
    val onPrivateStorage = remember(gamePath) { WowGameStorage.isPrivate(context, gamePath) }
    fun gigabytes(bytes: Long) = String.format(java.util.Locale.US, "%.1f", bytes / 1073741824.0)

    // Moves the game into the other storage, then makes it the active one. A journal (exact folders + direction) is written first,
    // so a killed or cancelled move is finished from the same folders on the next start. [resume] is that journal.
    fun switchStorage(toPrivate: Boolean, resume: WowGameStorage.PendingMove? = null) {
        if (storageBusy || isLaunching || isUpdating || isPreparingBundledComponents || PluviaApp.keepAlive) return
        val from = resume?.from ?: File(gamePath)
        val to = resume?.to ?: if (toPrivate) WowGameStorage.privateRoot(context) else WowGameStorage.sharedPath(context)
        storageBusy = true
        storageProgress = 0f
        storageMessage = t("Preparing...", "Előkészítés...")
        errorMessage = null
        scope.launch(Dispatchers.IO) {
            try {
                if (resume == null) {
                    if (toPrivate) WowGameStorage.rememberShared(context, from)
                    // Without a journal nothing may move: an interrupted run could not be finished.
                    check(WowGameStorage.beginMove(context, toPrivate, from, to)) { "Cannot record the move." }
                }
                WowGameStorage.move(from, to, onChanged = {
                    // The journal must say "started" on disk before the first file changes: otherwise a kill could leave a half-moved game
                    // that an interrupted-move check no longer recognises. A failed write stops the move before it touches anything.
                    check(WowGameStorage.markMoveStarted(context)) { "Cannot record the move." }
                }) { p ->
                    storageProgress = if (p.totalBytes > 0) p.copiedBytes.toFloat() / p.totalBytes else 1f
                    storageMessage = t("Moving game files: ", "Játékfájlok áthelyezése: ") + "${gigabytes(p.copiedBytes)} / ${gigabytes(p.totalBytes)} GB"
                }
                withContext(Dispatchers.Main) {
                    gamePath = to.absolutePath
                    GamePath.save(context, gamePath)
                    WowGameStorage.endMove(context)
                    versionStatus = null
                    storageMessage = if (toPrivate) t("The game now runs from the app's private storage.", "A játék most az app saját tárhelyéről fut.")
                        else t("The game now runs from the shared storage.", "A játék most a megosztott tárhelyről fut.")
                    val ok = checkFiles()
                    if (!hasStorageAccess) showStoragePermissionDialog = true
                    else if (ok) checkVersionStatus()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val message = e.message.orEmpty()
                val parts = message.split(':')
                val noSpace = parts.firstOrNull() == "NOSPACE" && parts.size == 3
                // The journal only goes when this run changed nothing; a move that already touched files stays pending.
                if (noSpace && WowGameStorage.pendingMove(context, gamePath)?.started != true) WowGameStorage.endMove(context)
                errorMessage = if (noSpace)
                    t("Not enough free space: ${gigabytes(parts[1].toLong())} GB needed, ${gigabytes(parts[2].toLong())} GB free.",
                        "Nincs elég szabad hely: ${gigabytes(parts[1].toLong())} GB kell, ${gigabytes(parts[2].toLong())} GB szabad.")
                else t("Storage switch interrupted: ", "A tárhely váltása megszakadt: ") + message + t(" The app finishes it on the next start.", " A következő indításkor az app befejezi.")
                storageMessage = null
            } finally {
                storageBusy = false
            }
        }
    }

    val filesMissing = !(dataExists && buildInfoExists)

    fun launchGame() {
        // An addon install/update/remove still writing to AddOns finishes first (the button is disabled meanwhile).
        if (isPreparingBundledComponents || isLaunching || isWrongGame || storageBusy || WowAddonJobs.running > 0) return
        // Play during the full game-data download: the download stops (keeping what it has, the storage closed and saved) and the
        // game starts; it then fetches what it needs itself, and CONTINUE later goes on at full speed.
        if (WowDataDownloadState.running) {
            WowDataDownloadState.cancel = true
            isLaunching = true
            statusText = t("Stopping the game data download...", "A játékadatok letöltésének leállítása...")
            scope.launch {
                while (WowDataDownloadState.running) kotlinx.coroutines.delay(200)
                isLaunching = false
                launchGame()
            }
            return
        }
        if (!hasStorageAccess) {
            showStoragePermissionDialog = true
            return
        }

        // A launch (manual or automatic) consumes the one-time start-up auto launch: coming back from the game must not start it again.
        WoWLauncherState.shouldAutoLaunch = false
        WowLaunchProgress.begin()
        isLaunching = true
        errorMessage = null
        // The game is landscape: turn the screen at the tap, so no portrait frame is shown while it prepares.
        PluviaApp.events.emit(AndroidEvent.SetAllowedOrientation(app.gamenative.PrefManager.allowedOrientation))
        statusText = t("Loading...", "Betöltés...")

        launchJob = scope.launch(Dispatchers.IO) {
            var handedOff = false
            try {
                // Exit does not wait for the last session's wineserver any more: it saves its registry in the background first.
                check(PluviaApp.awaitTeardown(30_000)) {
                    t("The previous game session is still closing. Try again in a moment.", "Az előző játékmenet még záródik. Próbáld újra egy pillanat múlva.")
                }
                WowDiagnostics.lifecycle(context,"launcher.prepare")
                // A storage the game marked for repair because a download stop left a data file without its segment headers.
                runCatching { WowGameData.repairIfMarked(context, File(gamePath)) }
                val componentsOk =
                    installBundledComponents(context) { msg ->
                        scope.launch(Dispatchers.Main) { statusText = msg }
                    }

                if (!componentsOk) {
                    throw IllegalStateException(t("Failed to extract Proton or graphics components.", "A Proton vagy a grafikus komponensek kibontása nem sikerült."))
                }
                WowLaunchProgress.reach(0.08f, 0.12f, 1500)

                check(File(gamePath, ".build.info").exists()) {
                    t("Missing .build.info in $gamePath. Copy it from your WoW install.", "Hiányzik a .build.info innen: $gamePath. Másold át a WoW telepítésedből.")
                }

                val arm64Exe = File(gamePath, "${WowClientDownloader.FLAVOR_DIR}/${WowClientDownloader.EXECUTABLE}")
                if (!arm64Exe.exists()) {
                    try {
                        WowClientDownloader.download(File(gamePath)) { msg ->
                            scope.launch(Dispatchers.Main) { statusText = msg }
                        }
                    } catch (e: Exception) {
                        if (!arm64Exe.exists()) {
                            throw IllegalStateException("Couldn't download ${WowClientDownloader.EXECUTABLE}: ${e.message}", e)
                        }

                    }
                }

                val isAdreno8xx = isAdreno8xxDevice(context)
                val gpuLabel = if (isAdreno8xx) "Adreno 8xx" else "Adreno 740"

                scope.launch(Dispatchers.Main) { statusText = t("Configuring $gpuLabel container...", "$gpuLabel konténer beállítása...") }
                val containerManager =  ContainerManager(context)
                val containerId = WowContainerProfile.CONTAINER_ID

                var container = containerManager.getContainerById(containerId)
                // This phone's Full screen resolution (also remembered for the game screen's camera-cutout layout).
                val fullscreen = WowDisplaySettings.fullscreen(context)
                if (container == null) {
                    scope.launch(Dispatchers.Main) { statusText = t("Creating prefix environment (first boot)...", "Prefix létrehozása (első indítás)...") }
                    val newContainerDefaults = WowContainerProfile.defaultsForNewContainer(
                        gamePath = gamePath,
                        isAdreno8xx = isAdreno8xx,
                        isSamsung = Build.MANUFACTURER.equals("samsung", ignoreCase = true),
                        fullscreen = fullscreen,
                    )
                    container = containerManager.createContainer(containerId, newContainerDefaults)
                } else if (WowContainerProfile.prepareExistingContainer(container, gamePath) or WowContainerProfile.moveToFullscreen(container, fullscreen)) {
                    container.saveData()
                }

                if (container == null || !containerManager.hasContainer(containerId)) {
                    throw IllegalStateException(t("Container creation failed. Check system storage and logs.", "A konténer létrehozása nem sikerült. Ellenőrizd a tárhelyet és a naplókat."))
                }

                try {
                    WowGameConfig.prepare(File(gamePath), WowDisplaySettings.gameResolution(container), if (WowVkd3dComponents.api(container) == WowVkd3dComponents.DX12) "D3D12" else "D3D11",
                        WowRuntimeTuning.gameAccount(context), WowRuntimeTuning.region(context), WowRuntimeTuning.gameLocale(context))
                } catch (e: Exception) {
                    if (e.message?.contains("EPERM") == true || e.message?.contains("Operation not permitted") == true) {
                        scope.launch(Dispatchers.Main) {
                            hasStorageAccess = false
                            isLaunching = false
                            statusText = t("Ready to launch", "Indításra kész")
                            showStoragePermissionDialog = true
                        }
                        return@launch
                    }
                    throw e
                }


                if (!isActive) return@launch
                WowDiagnostics.lifecycle(context,"launcher.navigate_xserver")
                withContext(Dispatchers.Main) {
                    statusText = t("Loading...", "Betöltés...")
                    handedOff = true
                    onLaunch(containerId)
                }
            } catch (e: Exception) {
                if (e is CancellationException) {  return@launch }

                WowDiagnostics.lifecycle(context,"launcher.failed",e)
                scope.launch(Dispatchers.Main) {
                    errorMessage = e.message ?: t("Launch failed", "Az indítás nem sikerült")
                    statusText = t("Launch failed", "Az indítás nem sikerült")
                    isLaunching = false
                }
            }
        }
    }

    /** A new install without a PC, or the in-place upgrade of a Classic beta install, for [region]. */
    fun performInstall(region: String) {
        if (isPreparingBundledComponents || isUpdating || isLaunching || storageBusy) return
        if (!hasStorageAccess) {
            showStoragePermissionDialog = true
            return
        }
        isUpdating = true
        updateStatusText = t("Connecting to Blizzard CDN...", "Csatlakozás a Blizzard CDN-hez...")
        errorMessage = null
        scope.launch(Dispatchers.IO) {
            try {
                WowClientDownloader.install(File(gamePath), region) { msg ->
                    scope.launch(Dispatchers.Main) { updateStatusText = msg }
                }
                withContext(Dispatchers.Main) {
                    checkFiles()
                    versionStatus = null
                    isUpdating = false
                }
            } catch (e: Exception) {
                scope.launch(Dispatchers.Main) {
                    if (e.message?.contains("EPERM") == true || e.message?.contains("Operation not permitted") == true) {
                        hasStorageAccess = false
                        showStoragePermissionDialog = true
                    }
                    errorMessage = t("Installation failed: ", "A telepítés nem sikerült: ") + e.message
                    isUpdating = false
                }
            }
        }
    }

    fun performUpdate() {
        val target = versionStatus ?: return
        if (isPreparingBundledComponents || isUpdating || isLaunching || storageBusy) return
        if (!hasStorageAccess) {
            showStoragePermissionDialog = true
            return
        }
        isUpdating = true
        updateStatusText = t("Connecting to Blizzard CDN...", "Csatlakozás a Blizzard CDN-hez...")
        errorMessage = null
        scope.launch(Dispatchers.IO) {
            try {
                WowClientDownloader.updateGame(File(gamePath), target, onStatus = { msg ->
                    scope.launch(Dispatchers.Main) { updateStatusText = msg }
                })
                withContext(Dispatchers.Main) {
                    checkFiles()
                    versionStatus = versionStatus?.copy(isOutdated = false, localVersion = target.remoteVersion)
                    isUpdating = false
                    launchGame()
                }
            } catch (e: Exception) {

                scope.launch(Dispatchers.Main) {
                    if (e.message?.contains("EPERM") == true || e.message?.contains("Operation not permitted") == true) {
                        hasStorageAccess = false
                        showStoragePermissionDialog = true
                    }
                    errorMessage = t("Update failed: ", "A frissítés nem sikerült: ") + e.message
                    isUpdating = false
                }
            }
        }
    }

    LaunchedEffect(gamePath) {
        // Automatic launch: start the (short, bounded) version check now so it runs while the components are prepared.
        val earlyCheck = if (WoWLauncherState.shouldAutoLaunch && File(gamePath, ".build.info").exists())
            async(Dispatchers.IO) { runCatching { WowClientDownloader.checkVersion(File(gamePath), fast = true) }.getOrNull() }
        else null
        isPreparingBundledComponents = true
        statusText = t("Preparing bundled components...", "Beépített komponensek előkészítése...")
        try {
            val installed = WowBundledStartup.install(
                onFailure = { message ->
                    errorMessage = message
                    statusText = t("Component installation failed", "A komponensek telepítése nem sikerült")
                    PluviaApp.events.emit(AndroidEvent.ClearBootingSplash)
                },
                action = {
                    // Installation starts on first launcher opening, even before game
                    // files are selected. No container is created and no profile applied.
                    if (!PluviaApp.keepAlive) {
                        withContext(Dispatchers.IO) {
                            installBundledComponents(context) { message ->
                                scope.launch(Dispatchers.Main) { statusText = message }
                            }
                        }
                    }
                },
            )
            if (!installed) {
                WoWLauncherState.shouldAutoLaunch = false
                return@LaunchedEffect
            }
        } finally {
            isPreparingBundledComponents = false
        }
        statusText = t("Ready to launch", "Indításra kész")
        // A game move that was interrupted: finish it before anything reads the half-moved folders.
        WowGameStorage.pendingMove(context, gamePath)?.let { pending ->
            WoWLauncherState.shouldAutoLaunch = false
            PluviaApp.events.emit(AndroidEvent.ClearBootingSplash)
            if (!pending.from.isDirectory && WowGameStorage.targetComplete(pending.to)) {
                // Every file had already arrived; only the active path was not switched yet.
                gamePath = pending.to.absolutePath
                GamePath.save(context, gamePath)
                WowGameStorage.endMove(context)
            } else if (!pending.from.isDirectory) {
                WowGameStorage.endMove(context)
                errorMessage = t("An interrupted game move could not be resumed: the source folder is gone.", "Egy megszakadt játékmozgatás nem folytatható: a forrásmappa nincs meg.")
            } else {
                switchStorage(pending.toPrivate, pending)
                return@LaunchedEffect
            }
        }
        val ready = checkFiles()
        // Everything the launcher does with the game folder needs All files access; a fresh install asks at once (1.4.7).
        if (!hasStorageAccess) {
            showStoragePermissionDialog = true
        }

        // Without the game data the home screen shows the GAME DATA card first instead of starting the game by itself.
        if (ready && WoWLauncherState.shouldAutoLaunch && withContext(Dispatchers.IO) { WowGameData.downloaded(File(gamePath)) } < WowCasc.GIB) {
            WoWLauncherState.shouldAutoLaunch = false
            PluviaApp.events.emit(AndroidEvent.ClearBootingSplash)
        }
        if (ready) {
            if (WoWLauncherState.shouldAutoLaunch) {
                isCheckingVersion = true
                // The early check already finished or timed out when it returns null: asking again would repeat the same failing request
                // (and a second 2.5 s wait offline). Only start a check here when none was started at the beginning.
                val check = if (earlyCheck != null) withTimeoutOrNull(2500) { earlyCheck.await() }
                    else withContext(Dispatchers.IO) { withTimeoutOrNull(2500) { WowClientDownloader.checkVersion(File(gamePath), fast = true) } }

                versionStatus = check
                isCheckingVersion = false
                WoWLauncherState.shouldAutoLaunch = false
                if (check?.isOutdated == true) {
                    PluviaApp.events.emit(AndroidEvent.ClearBootingSplash)
                } else {
                    launchGame()
                }
            } else {
                checkVersionStatus()
            }
        } else {
            // Not ready (files, permission): the one-time auto launch is over; a later return to the launcher must not start the game.
            WoWLauncherState.shouldAutoLaunch = false
            PluviaApp.events.emit(AndroidEvent.ClearBootingSplash)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        WowTheme.palette.bgTop,
                        WowTheme.palette.bgMid,
                        WowTheme.palette.bgBottom
                    )
                )
            )
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(24.dp)
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val headerBlock: @Composable ColumnScope.() -> Unit = {
            // Header
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Spacer(modifier = Modifier.height(8.dp))

                // Classic Era Launcher name (the logo is on the splash and the app icon); the game itself is named on the Play button.
                Text(
                    text = "CLASSIC ERA",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Black,
                    fontFamily = FontFamily.Serif,
                    letterSpacing = 6.sp,
                    maxLines = 1,
                    color = WowTheme.palette.title,
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = "LAUNCHER",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 7.sp,
                    maxLines = 1,
                    color = WowTheme.palette.accent,
                    textAlign = TextAlign.Center,
                )

                Spacer(modifier = Modifier.height(6.dp))

                Text(
                    text = if (isAdreno8xxDevice(context)) "Snapdragon 8 Elite / Adreno 8xx Edition" else "Snapdragon 8 Series / Adreno 740 Edition",
                    fontSize = 11.sp,
                    color = WowTheme.palette.muted,
                    textAlign = TextAlign.Center
                )
            }
            }
            // The whole game data at full speed, instead of the game's own slow download while it runs. Portrait: under the
            // ENVIRONMENT card; landscape: at the top of the right column, next to Play, so it is never below the screen edge.
            val showDataCard = !filesMissing && !isWrongGame &&
                (gameData?.complete == false || WowDataDownloadState.running || WowDataDownloadState.error != null)
            val dataBlock: @Composable ColumnScope.() -> Unit = {
                if (showDataCard) {
                    WowGameDataCard(gameData, enabled = !isLaunching && !isUpdating && !storageBusy && !isPreparingBundledComponents && !PluviaApp.keepAlive,
                        gameRoot = gamePath, onChanged = { gameDataRevision++ })
                }
            }
            val infoBlock: @Composable ColumnScope.() -> Unit = {
            // Status card: the checklist and the game path. Game storage moved to Settings > Advanced.
            val checks = buildList {
                add((if (exeExists) "ARM64 Binary" else tx("ARM64 Binary (downloads on Play)", "ARM64 Binary (indításkor letöltődik)")) to exeExists)
                // Green only when the game data is complete (WowGameData); while it downloads, orange with the percent.
                val live = WowDataDownloadState.progress?.takeIf { WowDataDownloadState.running && it.total > 0 }
                val data = live?.let { WowGameData.Progress(it.done, it.total) } ?: gameData
                add(when {
                    data == null || data.complete -> tx("Game Asset Archives", "Játékfájlok") to dataExists
                    data.total <= 0 || data.downloaded <= 0 -> tx("Game Asset Archives (not downloaded yet)", "Játékfájlok (még nincsenek letöltve)") to false
                    else -> tx("Game Asset Archives (${data.percent}%)", "Játékfájlok (${data.percent}%)") to false
                })
                add((if (isWrongGame) tx("Install Info (.build.info - requires wow_classic_era)", "Telepítési infó (.build.info - wow_classic_era kell)")
                    else if (buildInfoExists && !buildInfoValid) tx("Install Info (.build.info unreadable - reinstall or repair the game)", "Telepítési infó (a .build.info olvashatatlan - telepítsd újra vagy javítsd a játékot)")
                    else tx("Install Info (.build.info)", "Telepítési infó (.build.info)")) to (buildInfoValid && !isWrongGame))
                add(tx("All Files Access Permission", "Teljes fájlhozzáférés engedélye") to hasStorageAccess)
                versionStatus?.let { v ->
                    add((if (v.isOutdated) tx("Build: ${v.localVersion} (update ${v.remoteVersion} available)", "Build: ${v.localVersion} (${v.remoteVersion} frissítés elérhető)")
                        else tx("Build: ${v.localVersion} (up to date)", "Build: ${v.localVersion} (naprakész)")) to !v.isOutdated)
                }
            }
            Card(
                modifier = Modifier
                    .fillMaxWidth(0.9f)
                    .border(1.dp, WowTheme.palette.cardBorder, RoundedCornerShape(16.dp)),
                colors = CardDefaults.cardColors(containerColor = WowTheme.palette.card.copy(alpha = 0.85f)),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = tx("ENVIRONMENT", "KÖRNYEZET"),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp,
                            color = WowTheme.palette.accent,
                            modifier = Modifier.weight(1f),
                        )
                        if (checks.all { it.second }) {
                            Text(text = "✓ " + tx("All set", "Minden kész"), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFF48BB78))
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    checks.forEach { (label, ready) -> CheckItem(label = label, ready = ready); Spacer(modifier = Modifier.height(4.dp)) }
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(text = tx("Path: ", "Útvonal: ") + gamePath, fontSize = 11.sp, color = WowTheme.palette.muted, maxLines = 2)
                    if (storageBusy) {
                        Spacer(modifier = Modifier.height(6.dp))
                        LinearProgressIndicator(
                            progress = { storageProgress },
                            modifier = Modifier.fillMaxWidth(),
                            color = WowTheme.palette.accent,
                            trackColor = WowTheme.palette.divider,
                        )
                    }
                    storageMessage?.let {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(it, fontSize = 11.sp, color = WowTheme.palette.accent)
                    }
                    if (errorMessage != null) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = tx("Error: ", "Hiba: ") + errorMessage,
                            fontSize = 12.sp,
                            color = Color(0xFFFC8181),
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
            if (versionStatus?.isOutdated == true) {
                Spacer(modifier = Modifier.height(16.dp))
                Card(
                    modifier = Modifier
                        .fillMaxWidth(0.9f)
                        .border(1.dp, Color(0xFFE53E3E).copy(alpha = 0.6f), RoundedCornerShape(16.dp)),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF2D1515).copy(alpha = 0.85f)),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.Warning,
                                contentDescription = null,
                                tint = Color(0xFFFC8181),
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = tx("GAME UPDATE REQUIRED", "JÁTÉKFRISSÍTÉS SZÜKSÉGES"),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.sp,
                                color = Color(0xFFFC8181)
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = tx("Live servers require build ${versionStatus?.remoteVersion}, but local files are build ${versionStatus?.localVersion}. Logging in may fail or disconnect at realm selection.", "Az élő szerverek ${versionStatus?.remoteVersion} buildet kérnek, a helyi fájlok ${versionStatus?.localVersion} buildek. A belépés sikertelen lehet, vagy realmválasztáskor bonthat."),
                            fontSize = 12.sp,
                            color = Color(0xFFFED7D7),
                            lineHeight = 16.sp
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = tx("Tap Update below to download the latest files directly from Blizzard's CDN over Wi-Fi.", "Az alábbi Frissítés gombbal letöltheted a legújabb fájlokat közvetlenül a Blizzard CDN-ről (Wi-Fi-n)."),
                            fontSize = 11.sp,
                            color = WowTheme.palette.label
                        )
                    }
                }
            }
            }
            val actionBlock: @Composable ColumnScope.() -> Unit = {
            // Action section
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (isPreparingBundledComponents || isLaunching || isUpdating || (isCheckingVersion && WoWLauncherState.shouldAutoLaunch)) {
                    CircularProgressIndicator(
                        color = WowTheme.palette.accent,
                        modifier = Modifier.size(36.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = when {
                            // The downloader reports English templates (WowClientDownloader.Text); shown in the UI language.
                            isUpdating -> localizedStatus(updateStatusText)
                            isPreparingBundledComponents || isLaunching -> localizedStatus(statusText)
                            else -> tx("Checking for game updates...", "Játékfrissítés ellenőrzése...")
                        },
                        fontSize = 13.sp,
                        color = WowTheme.palette.text,
                        textAlign = TextAlign.Center
                    )
                } else {
                    // No install yet, or a Classic beta install: download the game here (about 250 MB, the rest streams while playing).
                    if (!buildInfoExists) {
                        Button(
                            onClick = { showInstallDialog = true },
                            enabled = !isPreparingBundledComponents && !isLaunching && !isUpdating && !storageBusy && WowAddonJobs.running == 0,
                            modifier = Modifier.fillMaxWidth(0.85f).height(46.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = WowTheme.palette.accentFill, contentColor = Color.White),
                            shape = RoundedCornerShape(14.dp),
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(22.dp))
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(tx("DOWNLOAD THE GAME", "JÁTÉK LETÖLTÉSE"),
                                fontSize = 15.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = tx("Downloads the Classic Era ARM64 client first. Game data can download here or while you play.",
                                "PC nem kell: most kb. 250 MB, a többit a játék játék közben tölti le."),
                            fontSize = 11.sp, color = WowTheme.palette.muted, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(0.85f),
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                    if (filesMissing) {
                        OutlinedButton(
                            onClick = { folderPicker.launch(null) },
                            modifier = Modifier.fillMaxWidth(0.85f).height(42.dp),
                            shape = RoundedCornerShape(14.dp),
                        ) {
                            Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(20.dp), tint = WowTheme.palette.accent)
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(tx("LOCATE GAME FILES", "JÁTÉKFÁJLOK KIVÁLASZTÁSA"), fontSize = 13.sp, fontWeight = FontWeight.Bold, color = WowTheme.palette.accent)
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                    }

                    if (versionStatus?.isOutdated == true) {
                        Button(
                            onClick = { performUpdate() },
                            enabled = !isPreparingBundledComponents && !isLaunching && !isUpdating && !storageBusy && !filesMissing && !isWrongGame && WowAddonJobs.running == 0,
                            modifier = Modifier
                                .fillMaxWidth(0.85f)
                                .height(46.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = WowTheme.palette.accentFill,
                                contentColor = Color.White
                            ),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(22.dp))
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = tx("UPDATE TO ", "FRISSÍTÉS: ") + versionStatus?.remoteVersion,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.sp
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        OutlinedButton(
                            onClick = { if (!lowDataAcknowledged && gameData?.let { it.lowData } == true) showLowDataDialog = true else launchGame() },
                            enabled = !isPreparingBundledComponents && !isLaunching && !isUpdating && !storageBusy && !filesMissing && !isWrongGame && WowAddonJobs.running == 0,
                            modifier = Modifier
                                .fillMaxWidth(0.85f)
                                .height(42.dp),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Text(
                                text = tx("LAUNCH ANYWAY (OUTDATED)", "INDÍTÁS ÍGY IS (ELAVULT)"),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFFC8181)
                            )
                        }
                    } else if (!hasStorageAccess) {
                        Button(
                            onClick = { StorageUtils.requestManageExternalStoragePermission(context) },
                            enabled = !isPreparingBundledComponents && !isLaunching && !isUpdating,
                            modifier = Modifier
                                .fillMaxWidth(0.85f)
                                .height(46.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = WowTheme.palette.accentFill,
                                contentColor = Color.White
                            ),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(22.dp))
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = tx("GRANT STORAGE PERMISSION", "TÁRHELY-ENGEDÉLY MEGADÁSA"),
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.sp
                            )
                        }
                    } else {
                        Button(
                            onClick = { if (!lowDataAcknowledged && gameData?.let { it.lowData } == true) showLowDataDialog = true else launchGame() },
                            enabled = !isPreparingBundledComponents && !isLaunching && !isUpdating && !storageBusy && !filesMissing && !isWrongGame && WowAddonJobs.running == 0,
                            modifier = Modifier
                                .fillMaxWidth(0.85f)
                                .height(46.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = WowTheme.palette.accentFill,
                                contentColor = Color.White
                            ),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(22.dp))
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = if (isWrongGame) tx("CLASSIC ERA REQUIRED", "WOW FOREVER SZÜKSÉGES") else "WORLD OF WARCRAFT",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.sp
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedButton(
                        onClick = onOpenAddons,
                        enabled = !isPreparingBundledComponents && !isLaunching && !isUpdating,
                        modifier = Modifier.fillMaxWidth(0.85f).height(42.dp),
                        shape = RoundedCornerShape(14.dp),
                    ) {
                        Icon(Icons.Default.Extension, contentDescription = null, modifier = Modifier.size(20.dp), tint = WowTheme.palette.accent)
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(tx("Addons", "Addonok"), fontSize = 13.sp, fontWeight = FontWeight.Bold, color = WowTheme.palette.accent)
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedButton(
                        onClick = { showSettings = true },
                        enabled = !isPreparingBundledComponents && !isLaunching && !isUpdating,
                        modifier = Modifier.fillMaxWidth(0.85f).height(42.dp),
                        shape = RoundedCornerShape(14.dp),
                    ) {
                        Icon(Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(20.dp), tint = WowTheme.palette.accent)
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(tx("Settings", "Beállítások"), fontSize = 13.sp, fontWeight = FontWeight.Bold, color = WowTheme.palette.accent)
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(0.95f),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        @Composable
                        fun SmallAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
                            TextButton(
                                onClick = onClick,
                                modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp),
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp), tint = WowTheme.palette.mutedLight)
                                    Text(label, fontSize = 10.sp, color = WowTheme.palette.mutedLight, textAlign = TextAlign.Center, maxLines = 2, lineHeight = 12.sp)
                                }
                            }
                        }

                        SmallAction(Icons.Default.Refresh, tx("Refresh Status", "Állapot frissítése")) {
                            scope.launch { checkFiles(); checkVersionStatus(force = true) }
                        }
                        if (!filesMissing) {
                            SmallAction(Icons.Default.FolderOpen, tx("Change Location", "Hely módosítása")) { folderPicker.launch(null) }
                        }
                        if (hasSavedLogin) {
                            SmallAction(Icons.Default.Delete, tx("Forget Saved Login", "Mentett belépés törlése")) {
                                BattleNetSignIn.forget(context)
                                hasSavedLogin = false
                            }
                        }
                    }

                    nextLaunch?.let { st ->
                        Spacer(modifier = Modifier.height(14.dp))
                        Column(
                            modifier = Modifier.fillMaxWidth(0.9f),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(3.dp),
                        ) {
                            Text(
                                text = tx("NEXT LAUNCH", "KÖVETKEZŐ INDÍTÁS"),
                                fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp, color = WowTheme.palette.accent,
                            )
                            listOf(st.api, st.runtime, st.graphicsLayer, st.driver).filter { it.isNotBlank() }.forEach { line ->
                                Text(text = line, fontSize = 11.sp, color = WowTheme.palette.muted, textAlign = TextAlign.Center, lineHeight = 14.sp)
                            }
                            Text(
                                text = tx("GPU: ", "GPU: ") + when {
                                    st.gpuMode == "performance" -> "Performance"
                                    st.gpuMode == "balanced" -> "Balanced"
                                    st.gpuMode.startsWith("floor") -> tx("Regulated ", "Szabályozott ") + st.gpuMode.removePrefix("floor") + "%"
                                    else -> tx("default", "alapértelmezett")
                                },
                                fontSize = 11.sp, color = WowTheme.palette.muted, textAlign = TextAlign.Center, lineHeight = 14.sp,
                            )
                            Text(
                                text = tx("FPS limit: ", "FPS-limit: ") + if (st.fps == "off") tx("off", "ki") else st.fps,
                                fontSize = 11.sp, color = WowTheme.palette.muted, textAlign = TextAlign.Center, lineHeight = 14.sp,
                            )
                        }
                    }
                }
            }
            }
            // The launcher rotates freely. Landscape: two columns, Play and Settings visible without scrolling.
            // Portrait: one column. In both, everything is packed (nothing is spread over the screen height); a column that does
            // not fit (the NEXT LAUNCH lines at the bottom in portrait) scrolls instead of being cut off.
            val screenHeight = maxHeight
            if (maxWidth > maxHeight) {
                Row(modifier = Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    Column(
                        modifier = Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        headerBlock()
                        Spacer(modifier = Modifier.height(12.dp))
                        infoBlock()
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                    Column(
                        modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()).heightIn(min = screenHeight),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        if (showDataCard) { dataBlock(); Spacer(modifier = Modifier.height(12.dp)) }
                        actionBlock()
                    }
                }
            } else {
                // Centred while it fits, scrollable when it does not.
                Column(
                    modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).heightIn(min = screenHeight),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    headerBlock()
                    Spacer(modifier = Modifier.height(12.dp))
                    infoBlock()
                    if (showDataCard) { Spacer(modifier = Modifier.height(12.dp)); dataBlock() }
                    Spacer(modifier = Modifier.height(16.dp))
                    actionBlock()
                }
            }
        }
    }

    if (showSettings && !isPreparingBundledComponents && !isLaunching && !isUpdating) {
        WowSettingsDialog(
            onDismissRequest = { showSettings = false },
            language = language,
            onLanguageChange = onLanguageChange,
            gameStorage = if (filesMissing) null else { {
                Row(verticalAlignment = Alignment.Top, modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            // Same look as the other settings rows (e.g. "Microphone capture"): plain label, small hint under it.
                            text = tx("Store the game in the app's private storage", "Játék tárolása az app saját (nem megosztott) tárhelyén"),
                        )
                        Text(
                            text = tx(
                                "Faster loading: direct file access instead of Android's shared-storage layer. The game is MOVED (never stored twice); switching back moves it back. Updates work in both places.",
                                "Gyorsabb betöltés: közvetlen fájlelérés az Android megosztott tárhely-rétege helyett. A játék ÁTKERÜL (sosem lesz két példány); visszakapcsolva visszakerül. A frissítés mindkét helyen működik.",
                            ),
                            style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    // The same 80 % switch as every other settings row, level with the title's first line.
                    app.gamenative.ui.component.dialog.WowSwitch(
                        checked = onPrivateStorage,
                        enabled = !storageBusy && !isLaunching && !isUpdating && !isPreparingBundledComponents,
                        onCheckedChange = { switchStorage(it) },
                    )
                }
                if (storageBusy) LinearProgressIndicator(progress = { storageProgress }, modifier = Modifier.fillMaxWidth(),
                    color = WowTheme.palette.accent, trackColor = WowTheme.palette.divider)
                storageMessage?.let { Text(it, fontSize = 11.sp, color = WowTheme.palette.accent) }
            } },
        )
    }

    if (showInstallDialog) {
        var region by remember { mutableStateOf(WowClientDownloader.region(File(gamePath))) }
        val target = installTarget ?: gamePath
        AlertDialog(
            onDismissRequest = { showInstallDialog = false; installTarget = null },
            title = {
                Text(tx("Download World of Warcraft Classic Era", "World of Warcraft Classic Era letöltése"),
                    fontWeight = FontWeight.Bold, fontSize = 17.sp, color = WowTheme.palette.accent)
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(tx("Region (your account's game region):", "Régió (a fiókod játékrégiója):"), fontSize = 13.sp, color = WowTheme.palette.text)
                    listOf("eu" to tx("Europe", "Európa"), "us" to tx("Americas", "Amerika"), "kr" to tx("Korea", "Korea"), "tw" to tx("Taiwan", "Tajvan")).forEach { (value, label) ->
                        Row(
                            Modifier.fillMaxWidth().clickable { region = value }.padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = region == value, onClick = { region = value })
                            Text(label, fontSize = 14.sp, color = WowTheme.palette.text)
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(tx("Folder:", "Mappa:"), fontSize = 13.sp, color = WowTheme.palette.text)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(target, fontSize = 11.sp, color = WowTheme.palette.muted, modifier = Modifier.weight(1f))
                        TextButton(onClick = { installFolderPicker.launch(null) }) { Text(tx("Change", "Módosítás"), color = WowTheme.palette.accent) }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showInstallDialog = false
                        installTarget?.takeIf { it != gamePath }?.let { gamePath = it; GamePath.save(context, it) }
                        installTarget = null
                        performInstall(region)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = WowTheme.palette.accentFill, contentColor = Color.White),
                    shape = RoundedCornerShape(10.dp),
                ) { Text(tx("DOWNLOAD", "LETÖLTÉS"), fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { showInstallDialog = false; installTarget = null }) { Text(tx("CANCEL", "MÉGSE"), color = WowTheme.palette.mutedLight) }
            },
            containerColor = Color(0xFF1B2838),
            shape = RoundedCornerShape(16.dp),
        )
    }

    restartForAccess?.let {
        AlertDialog(
            onDismissRequest = { restartForAccess = null },
            title = { Text(tx("Restart the launcher?", "Újraindítsam az appot?"), fontWeight = FontWeight.Bold, fontSize = 17.sp, color = WowTheme.palette.accent) },
            text = {
                Text(tx("All files access is on, but Android still blocks this folder for the running app. On some phones the permission only applies after the app starts again. The folder is saved and checked again after the restart.",
                    "A Teljes fájlhozzáférés be van kapcsolva, de az Android a futó appnak még tiltja ezt a mappát. Egyes telefonokon az engedély csak az app újraindítása után lép életbe. A mappát megjegyzem, és újraindítás után újra megnézem."),
                    fontSize = 13.sp, color = WowTheme.palette.text, lineHeight = 18.sp)
            },
            confirmButton = {
                Button(onClick = {
                    restartForAccess = null
                    val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)?.component?.let { android.content.Intent.makeRestartActivityTask(it) }
                    if (intent != null) context.startActivity(intent)
                    android.os.Process.killProcess(android.os.Process.myPid())
                }, colors = ButtonDefaults.buttonColors(containerColor = WowTheme.palette.accentFill, contentColor = Color.White), shape = RoundedCornerShape(10.dp)) {
                    Text(tx("RESTART", "ÚJRAINDÍTÁS"), fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = { TextButton(onClick = { restartForAccess = null }) { Text(tx("CANCEL", "MÉGSE"), color = WowTheme.palette.mutedLight) } },
            containerColor = Color(0xFF1B2838),
            shape = RoundedCornerShape(16.dp)
        )
    }

    LaunchedEffect(gamePath, isUpdating, WowDataDownloadState.running, gameDataRevision) {
        if (isUpdating || WowDataDownloadState.running) return@LaunchedEffect
        // At once from the disk (no network): with (almost) no game data the card shows right away, the size follows.
        gameData = withContext(Dispatchers.IO) {
            runCatching {
                val root = File(gamePath)
                val downloaded = WowGameData.downloaded(root)
                WowGameData.cachedTotal(context, root)?.let { WowGameData.Progress(downloaded, it) }
                    ?: WowGameData.Progress(downloaded, 0).takeIf { downloaded < WowCasc.GIB && File(root, ".build.info").isFile }
            }.getOrNull()
        }
        gameData = withContext(Dispatchers.IO) {
            runCatching { WowGameData.ensureTotal(context, File(gamePath)); WowGameData.progress(context, File(gamePath)) }.getOrNull()
        } ?: gameData
    }
    if (showLowDataDialog) {
        val d = gameData
        AlertDialog(
            onDismissRequest = { showLowDataDialog = false },
            title = { Text(tx("Game data still downloading", "A játékadatok még töltődnek")) },
            text = {
                Text(tx("${d?.percent ?: 0}% of the game data (${WowGameData.gb(d?.downloaded ?: 0)} / ${WowGameData.gb(d?.total ?: 0)} GB) is on the phone. WoW downloads the rest while it runs, so the first start and new areas load slowly. Wi-Fi gives the fastest download.",
                    "A játékadatok ${d?.percent ?: 0}%-a (${WowGameData.gb(d?.downloaded ?: 0)} / ${WowGameData.gb(d?.total ?: 0)} GB) van a telefonon. A többit a WoW futás közben tölti le, ezért az első indítás és az új területek lassan töltődnek be. Wi-Fin a leggyorsabb a letöltés."))
            },
            confirmButton = { TextButton(onClick = { showLowDataDialog = false; lowDataAcknowledged = true; launchGame() }) { Text(tx("Start", "Indítás")) } },
            dismissButton = { TextButton(onClick = { showLowDataDialog = false }) { Text(tx("Cancel", "Mégse")) } },
        )
    }
    if (showStoragePermissionDialog) {
        AlertDialog(
            onDismissRequest = { showStoragePermissionDialog = false },
            title = {
                Text(
                    text = tx("Storage Permission Required", "Tárhely-engedély szükséges"),
                    fontWeight = FontWeight.Bold,
                    fontSize = 17.sp,
                    color = WowTheme.palette.accent
                )
            },
            text = {
                Text(
                    text = tx("Android requires 'All files access' for Classic Era Launcher to read and update game files in this directory.\n\nPlease enable 'Allow access to manage all files' on the next screen.", "Az Android 'Teljes fájlhozzáférés' engedélyt kér, hogy a Classic Era Launcher olvashassa és frissíthesse a játékfájlokat ebben a mappában.\n\nA következő képernyőn engedélyezd a 'Hozzáférés az összes fájl kezeléséhez' opciót."),
                    fontSize = 13.sp,
                    color = WowTheme.palette.text,
                    lineHeight = 18.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showStoragePermissionDialog = false
                        StorageUtils.requestManageExternalStoragePermission(context)
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = WowTheme.palette.accentFill,
                        contentColor = Color.White
                    ),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text(tx("OPEN SETTINGS", "BEÁLLÍTÁSOK MEGNYITÁSA"), fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showStoragePermissionDialog = false }) {
                    Text(tx("CANCEL", "MÉGSE"), color = WowTheme.palette.mutedLight)
                }
            },
            containerColor = Color(0xFF1B2838),
            shape = RoundedCornerShape(16.dp)
        )
    }
}

object GamePath {
    private const val PREFS = "wow_forever"
    private const val KEY_GAME_PATH = "game_path"

    /** Where a new install goes unless the player picks another folder (1.0.1: "WoW Classic Era", like the PC install). */
    private val defaultPath = File(Environment.getExternalStorageDirectory(), "WoW Classic Era")
    /** The earlier default; an install there is still found. */
    private val legacyPath = File(Environment.getExternalStorageDirectory(), "WoW Classic Era")

    fun load(context: Context): String {
        val saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_GAME_PATH, null)
            // A path saved from a folder picker that named an unmounted volume (1.4.7) is healed onto the volume that has it.
            ?.let { StorageUtils.existingPath(it, StorageUtils.storageRoots(context)) ?: it }?.let(::File)
        val usable = listOfNotNull(saved, defaultPath, legacyPath).firstOrNull {
            val file = File(it, ".build.info")
            file.isFile && WowClientDownloader.readBuildInfo(file)?.get("Product") == WowClientDownloader.TARGET_PRODUCT
        }
        return (usable ?: saved ?: defaultPath).absolutePath
    }

    fun save(context: Context, path: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_GAME_PATH, path).apply()
    }

    fun isReady(context: Context, path: String = load(context)): Boolean {
        if (path.isEmpty()) return false
        val root = File(path)
        val exe = File(root, "${WowClientDownloader.FLAVOR_DIR}/${WowClientDownloader.EXECUTABLE}")
        val dataDir = File(root, "Data")
        val buildInfo = File(root, ".build.info")
        return exe.exists() &&
            (dataDir.exists() && dataDir.listFiles()?.isNotEmpty() == true) &&
            buildInfo.isFile &&
            WowClientDownloader.readBuildInfo(buildInfo)?.get("Product") == WowClientDownloader.TARGET_PRODUCT &&
            StorageUtils.hasStoragePermission(context, path)
    }

    /** What Android lets the app see of a folder, for the "not readable" message. */
    fun accessReport(dir: File): String {
        val volume = generateSequence(dir) { it.parentFile }.firstOrNull { it.parentFile?.path == "/storage" }
        return "exists=${dir.exists()} dir=${dir.isDirectory} read=${dir.canRead()} list=${dir.list()?.size} " +
            "volume=${volume?.path}:${volume?.list()?.size} manager=${Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()} " +
            "state=${runCatching { Environment.getExternalStorageState(dir) }.getOrDefault("?")}"
    }

    /** The game folder is the one with .build.info, whatever its name: the picked folder, its parent, or one folder below it. */
    fun findGameRoot(picked: File): File? =
        (sequenceOf(picked, picked.parentFile, File(picked, "WoW Classic Era"), File(picked, "WoW Classic Era")) +
            (picked.listFiles()?.filter { it.isDirectory }?.sortedBy { it.name }?.asSequence() ?: emptySequence()))
            .filterNotNull()
            .firstOrNull { File(it, ".build.info").isFile }
}

@Composable
private fun CheckItem(label: String, ready: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = if (ready) Icons.Default.CheckCircle else Icons.Default.Warning,
            contentDescription = null,
            tint = if (ready) Color(0xFF48BB78) else Color(0xFFED8936),
            modifier = Modifier.size(18.dp)
        )
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = label,
            fontSize = 13.sp,
            color = if (ready) WowTheme.palette.label else Color(0xFFED8936)
        )
    }
}


private fun isAdreno8xxDevice(context: Context): Boolean {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val soc = Build.SOC_MODEL
        if (soc.contains("SM8750", ignoreCase = true) || soc.contains("SM8850", ignoreCase = true)) return true
    }
    return Build.HARDWARE.contains("sun", ignoreCase = true)
}

/** When the bundled set was last verified in this process; the launch flow asks twice within seconds. */
@Volatile private var bundledVerifiedAt = 0L
private const val BUNDLED_RECHECK_MS = 120_000L

private fun installBundledComponents(context: Context, onStatus: (String) -> Unit): Boolean {
    val now = android.os.SystemClock.elapsedRealtime()
    // Skip the full profile/ELF validation and content sync if it just succeeded; the library check below stays cheap.
    if (bundledVerifiedAt == 0L || now - bundledVerifiedAt >= BUNDLED_RECHECK_MS) {
        WowBundledComponents.ensureInstalled(context, onStatus)
        WowBundledComponents.upgradeSuperseded(context, onStatus)
        bundledVerifiedAt = android.os.SystemClock.elapsedRealtime()
    }
    com.winlator.xenvironment.ImageFsInstaller.removeOldRedirectLibs(ImageFs.find(context).rootDir)
    return true
}
