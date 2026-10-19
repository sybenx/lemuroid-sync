package com.swordfish.lemuroid.app.shared.game.viewmodel

import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.SystemClock
import android.view.MotionEvent
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import com.swordfish.lemuroid.BuildConfig
import com.swordfish.lemuroid.R
import com.swordfish.lemuroid.app.mobile.feature.settings.SettingsManager
import com.swordfish.lemuroid.app.shared.game.ShaderChooser
import com.swordfish.lemuroid.app.shared.link.LocalLink
import com.swordfish.lemuroid.app.shared.link.NetLink
import com.swordfish.lemuroid.app.shared.rumble.RumbleManager
import com.swordfish.lemuroid.app.shared.settings.HDModeQuality
import com.swordfish.lemuroid.common.coroutines.MutableStateProperty
import com.swordfish.lemuroid.common.coroutines.launchOnState
import com.swordfish.lemuroid.common.view.disableTouchEvents
import com.swordfish.lemuroid.lib.core.CoreVariable
import com.swordfish.lemuroid.lib.core.CoreVariablesManager
import com.swordfish.lemuroid.lib.game.GameLoader
import com.swordfish.lemuroid.lib.game.GameLoaderError
import com.swordfish.lemuroid.lib.game.GameLoaderException
import com.swordfish.lemuroid.lib.library.GameSystem
import com.swordfish.lemuroid.lib.library.SystemCoreConfig
import com.swordfish.lemuroid.lib.library.db.entity.Game
import com.swordfish.lemuroid.lib.storage.RomFiles
import com.swordfish.libretrodroid.GLRetroView
import com.swordfish.libretrodroid.GLRetroViewData
import com.swordfish.libretrodroid.ImmersiveMode
import com.swordfish.libretrodroid.LibretroDroid
import com.swordfish.libretrodroid.NetplayConfig
import com.swordfish.libretrodroid.Variable
import com.swordfish.libretrodroid.ViewportAlignment
import com.swordfish.libretrodroid.VirtualFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.FileInputStream
import kotlin.time.Duration.Companion.seconds

@OptIn(FlowPreview::class)
class GameViewModelRetroGameView(
    private val appContext: Context,
    private val system: GameSystem,
    private val systemCoreConfig: SystemCoreConfig,
    private val settingsManager: SettingsManager,
    private val coreVariablesManager: CoreVariablesManager,
    private val sideEffects: GameViewModelSideEffects,
    private val rumbleManager: RumbleManager,
    private val scope: CoroutineScope,
) : DefaultLifecycleObserver {
    sealed interface GameState {
        data object Uninitialized : GameState

        data class Loading(val message: String) : GameState

        data class Loaded(
            val gameData: GameLoader.GameData,
            val retroViewData: GLRetroViewData,
        ) : GameState

        data object Ready : GameState
    }

    private val gameState: MutableStateFlow<GameState> = MutableStateFlow(GameState.Uninitialized)

    private val retroGameViewFlow = MutableStateFlow<GLRetroView?>(null)
    private var lastAppliedCoreVariables: Pair<GLRetroView, List<CoreVariable>>? = null
    var retroGameView: GLRetroView? by MutableStateProperty(retroGameViewFlow)

    fun getGameState(): Flow<GameState> {
        return gameState.debounce(200)
    }

    suspend fun initialize(
        applicationContext: Context,
        game: Game,
        systemCoreConfig: SystemCoreConfig,
        gameLoader: GameLoader,
        requestLoadSave: Boolean,
        linkedGame: Game? = null,
        netLink: NetLink? = null,
    ) {
        val currentState = gameState.value
        if (currentState != GameState.Uninitialized) return

        val autoSaveEnabled = settingsManager.autoSave()
        val filter = settingsManager.screenFilter()
        val hdMode = settingsManager.hdMode()
        val hdModeQuality = settingsManager.hdModeQuality()
        val alignment = settingsManager.viewportAlignment()
        val lowLatencyAudio = settingsManager.lowLatencyAudio()
        val enableRumble = settingsManager.enableRumble()
        val directLoad = settingsManager.allowDirectGameLoad()
        val enableImmersiveMode = settingsManager.enableImmersiveMode()

        val hasMicrophonePermission =
            ContextCompat.checkSelfPermission(
                applicationContext,
                android.Manifest.permission.RECORD_AUDIO,
            ) == PackageManager.PERMISSION_GRANTED

        val enableMicrophone = systemCoreConfig.supportsMicrophone && hasMicrophonePermission

        val loadingStatesFlow =
            gameLoader.load(
                applicationContext,
                game,
                requestLoadSave && autoSaveEnabled,
                systemCoreConfig,
                directLoad,
                linkedGame,
            )

        loadingStatesFlow
            .flowOn(Dispatchers.IO)
            .catch {
                val message =
                    if (it is GameLoaderException) {
                        getErrorMessage(it.error)
                    } else {
                        ""
                    }
                sideEffects.requestFailureFinish(message)
            }
            .debounce(200)
            .collect { loadingState ->
                val netSetup =
                    if (loadingState is GameLoader.LoadingState.Ready && netLink != null) {
                        gameState.value =
                            GameState.Loading(appContext.getString(R.string.game_link_net_connecting, netLink.peerName))
                        connectNetLink(netLink, loadingState.gameData) ?: return@collect
                    } else {
                        null
                    }

                gameState.value =
                    if (loadingState is GameLoader.LoadingState.Ready) {
                        Timber.i("Setting state to loaded")
                        val retroViewData =
                            buildRetroViewData(
                                applicationContext,
                                systemCoreConfig,
                                loadingState.gameData,
                                hdMode,
                                hdModeQuality,
                                filter,
                                alignment,
                                lowLatencyAudio,
                                enableRumble,
                                enableMicrophone,
                                enableImmersiveMode,
                                netSetup,
                            )
                        GameState.Loaded(
                            gameData = loadingState.gameData,
                            retroViewData = retroViewData,
                        )
                    } else {
                        GameState.Loading(getLoadingMessage(loadingState))
                    }
            }
    }

    fun createRetroView(
        context: Context,
        lifecycle: LifecycleOwner,
    ): Pair<GameLoader.GameData, GLRetroView> {
        val currentState = gameState.value
        if (currentState !is GameState.Loaded) throw IllegalStateException("Game is not loaded.")

        val result =
            GLRetroView(context, currentState.retroViewData)
                .apply {
                    isFocusable = false
                    isFocusableInTouchMode = false
                }

        if (!system.hasTouchScreen) {
            result.disableTouchEvents()
        }

        if (currentState.gameData.linkedGame != null) {
            linkedPlayersView = result
            result.setOnTouchListener { _, event -> selectLinkPlayerAt(result, event) }
        }

        netLocalPlayer?.let { player ->
            result.inputPortOverride = player
            watchNetLink(result)
        }

        lifecycle.lifecycle.addObserver(result)

        if (BuildConfig.DEBUG) {
            runCatching {
                printRetroVariables(result)
            }
        }

        retroGameViewFlow.value = result
        gameState.value = GameState.Ready

        return currentState.gameData to result
    }

    suspend fun retroGameViewFlow() =
        retroGameViewFlow
            .filterNotNull()
            .first()

    suspend fun waitRetroGameViewInitialized() {
        retroGameViewFlow()
    }

    suspend inline fun <reified T> waitGLEvent() {
        val retroView = retroGameViewFlow()
        retroView.getGLRetroEvents()
            .filterIsInstance<T>()
            .first()
    }

    /** lemuroid-sync: which linked Game Boy the controls drive. Null when no game is linked. */
    private var linkedPlayersView: GLRetroView? = null
    private val linkedPlayer = MutableStateFlow(0)
    private var linkedViewportAlignment = ViewportAlignment.CENTER
    private var linkedTapPlayer: Int? = null
    private var lastVirtualInputAt = 0L

    /** Called for every touch control event, so pressing the pad never counts as tapping a screen. */
    fun onVirtualInput() {
        lastVirtualInputAt = SystemClock.uptimeMillis()
    }

    private var linkedLoadedSaves: List<ByteArray?> = listOf()

    /** lemuroid-sync: set when the other Game Boy is on another device. */
    private var netLink: NetLink? = null
    private var netLocalPlayer: Int? = null
    private var wifiLock: WifiManager.WifiLock? = null

    /** This device's player when linked with another device, or null. */
    fun getNetLocalPlayer(): Int? = netLocalPlayer

    fun isLinked(): Boolean = linkedPlayersView != null

    /** Tells the player once the other device has left. The game goes on here with the other Game Boy idle. */
    private fun watchNetLink(retroView: GLRetroView) {
        scope.launch {
            while (true) {
                delay(1.seconds)
                if (retroView.getNetplayStatus() == LibretroDroid.NETPLAY_STATUS_ENDED) {
                    sideEffects.showToast(appContext.getString(R.string.game_link_net_ended, netLink?.peerName ?: ""))
                    releaseNetLink()
                    return@launch
                }
            }
        }
    }

    private fun releaseNetLink() {
        runCatching { wifiLock?.takeIf { it.isHeld }?.release() }
        wifiLock = null
        netLink?.close()
    }

    /**
     * The core refuses a save larger than its save memory, and that game then starts blank. Writing its
     * memory back would replace the real save, so only saves that fit were restored and may be written.
     */
    fun wasLinkedSaveRestored(
        player: Int,
        saveRAM: ByteArray,
    ): Boolean {
        val loaded = linkedLoadedSaves.getOrNull(player) ?: return true
        return loaded.size <= saveRAM.size
    }

    fun selectLinkPlayer(player: Int) {
        val retroGameView = linkedPlayersView ?: return
        if (linkedPlayer.value == player) return
        linkedPlayer.value = player
        retroGameView.inputPortOverride = player
        LocalLink.audioVariable(player).let { retroGameView.updateVariables(Variable(it.key, it.value)) }
        sideEffects.showToast(appContext.getString(R.string.game_link_local_player, player + 1))
    }

    /**
     * Tapping one of the two screens hands the controls to that Game Boy. Touches elsewhere, like the
     * virtual pad's buttons that reach this view too, never switch.
     */
    private fun selectLinkPlayerAt(
        view: GLRetroView,
        event: MotionEvent,
    ): Boolean {
        val player = findLinkedScreenAt(view, event.x, event.y)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> linkedTapPlayer = player
            MotionEvent.ACTION_UP -> {
                // The pad can sit on top of the screens (landscape), so a touch that pressed it is no tap.
                val padPressed = lastVirtualInputAt >= event.downTime - VIRTUAL_INPUT_TAP_WINDOW_MS
                if (player != null && player == linkedTapPlayer && !padPressed) selectLinkPlayer(player)
                linkedTapPlayer = null
            }
            MotionEvent.ACTION_CANCEL -> linkedTapPlayer = null
        }
        return true
    }

    /** The player whose screen is at ([x], [y]), or null outside both screens. */
    private fun findLinkedScreenAt(
        view: GLRetroView,
        x: Float,
        y: Float,
    ): Int? {
        val area = view.viewport
        val areaLeft = view.width * area.left
        val areaTop = view.height * area.top
        val areaWidth = view.width * area.width()
        val areaHeight = view.height * area.height()

        // Both screens together are 160x288 when stacked and 320x144 side by side, fitted in the area,
        // centered horizontally and aligned vertically like any other game.
        val stacked = LocalLink.isStacked(systemCoreConfig)
        val aspect = if (stacked) LINKED_SCREENS_STACKED_ASPECT else LINKED_SCREENS_SIDE_BY_SIDE_ASPECT
        val screensWidth = minOf(areaWidth, areaHeight * aspect)
        val screensHeight = screensWidth / aspect
        val screensLeft = areaLeft + (areaWidth - screensWidth) / 2f
        val screensTop =
            when (linkedViewportAlignment) {
                ViewportAlignment.TOP -> areaTop
                ViewportAlignment.BOTTOM -> areaTop + areaHeight - screensHeight
                else -> areaTop + (areaHeight - screensHeight) / 2f
            }

        val insideScreens =
            x >= screensLeft && x <= screensLeft + screensWidth && y >= screensTop && y <= screensTop + screensHeight
        if (!insideScreens) return null

        return if (stacked) {
            if (y < screensTop + screensHeight / 2f) 0 else 1
        } else {
            if (x < screensLeft + screensWidth / 2f) 0 else 1
        }
    }

    private fun buildRetroViewData(
        appContext: Context,
        systemCoreConfig: SystemCoreConfig,
        gameData: GameLoader.GameData,
        hdMode: Boolean,
        hdModeQuality: HDModeQuality,
        screenFilter: String,
        alignment: String,
        lowLatencyAudio: Boolean,
        requestRumble: Boolean,
        requestMicrophone: Boolean,
        enableImmersiveMode: Boolean,
        netSetup: NetSetup? = null,
    ): GLRetroViewData {
        return GLRetroViewData(appContext).apply {
            coreFilePath = gameData.coreLibrary

            val linkedGameFiles = gameData.linkedGameFiles
            if (netSetup != null) {
                // The host is player 1 on both devices.
                val own = netSetup.ownRom to gameData.saveRAMData
                val partner = netSetup.partner.rom to netSetup.partner.saveRAM
                val players = if (netSetup.localPlayer == 0) listOf(own, partner) else listOf(partner, own)
                setLinkedGames(players.map { it.first }, players.map { it.second })
                netplay =
                    NetplayConfig(netSetup.socketFd, netSetup.isHost, netSetup.localPlayer, NetLink.INPUT_DELAY_FRAMES)
            } else if (linkedGameFiles != null) {
                setLinkedGames(
                    listOf(readRomBytes(gameData.gameFiles), readRomBytes(linkedGameFiles)),
                    listOf(gameData.saveRAMData, gameData.linkedSaveRAMData),
                )
            } else {
                when (val gameFiles = gameData.gameFiles) {
                    is RomFiles.Standard -> {
                        gameFilePath = gameFiles.files.first().absolutePath
                    }

                    is RomFiles.Virtual -> {
                        gameVirtualFiles = gameFiles.files.map { VirtualFile(it.filePath, it.fd) }
                    }
                }
            }

            systemDirectory = gameData.systemDirectory.absolutePath
            savesDirectory = gameData.savesDirectory.absolutePath
            variables = gameData.coreVariables.map { Variable(it.key, it.value) }.toTypedArray()
            saveRAMState = gameData.saveRAMData
            shader =
                ShaderChooser.getShaderForSystem(
                    appContext,
                    hdMode,
                    hdModeQuality,
                    screenFilter,
                    GameSystem.findById(gameData.game.systemId),
                )
            viewportAlignment =
                when (alignment) {
                    "top" -> ViewportAlignment.TOP
                    "bottom" -> ViewportAlignment.BOTTOM
                    else -> ViewportAlignment.CENTER
                }
            linkedViewportAlignment = viewportAlignment
            preferLowLatencyAudio = lowLatencyAudio
            rumbleEventsEnabled = requestRumble
            skipDuplicateFrames = systemCoreConfig.skipDuplicateFrames
            enableMicrophone = requestMicrophone
            immersiveMode = buildImmersiveModeConfiguration(enableImmersiveMode)
        }
    }

    private fun GLRetroViewData.setLinkedGames(
        roms: List<ByteArray>,
        saves: List<ByteArray?>,
    ) {
        gameSpecialType = LocalLink.GAME_TYPE_LINK_2P
        gameSpecialFilesBytes = roms
        memoryStates =
            buildMap {
                saves[0]?.let { put(LocalLink.MEMORY_PLAYER_1_SAVE_RAM, it) }
                saves[1]?.let { put(LocalLink.MEMORY_PLAYER_2_SAVE_RAM, it) }
            }
        linkedLoadedSaves = saves
    }

    /** A connected link to another device, with that player's game. */
    class NetSetup(
        val ownRom: ByteArray,
        val partner: NetLink.Partner,
        val socketFd: Int,
        val isHost: Boolean,
        val localPlayer: Int,
    )

    private suspend fun connectNetLink(
        netLink: NetLink,
        gameData: GameLoader.GameData,
    ): NetSetup? {
        acquireWifiLock()
        this.netLink = netLink
        return try {
            val ownRom = withContext(Dispatchers.IO) { readRomBytes(gameData.gameFiles) }
            val socket = netLink.connect()
            val partner = netLink.exchange(socket, ownRom, gameData.saveRAMData, gameData.game.title)
            netLocalPlayer = netLink.localPlayer
            NetSetup(ownRom, partner, netLink.detach(socket), netLink.isHost, netLink.localPlayer)
        } catch (e: Exception) {
            Timber.e(e, "Unable to link with ${netLink.peerName}")
            sideEffects.requestFailureFinish(appContext.getString(R.string.game_link_net_failed, netLink.peerName))
            null
        }
    }

    /** Wi-Fi power saving can hold packets for a long time, and every frame waits for the other device. */
    @Suppress("DEPRECATION")
    private fun acquireWifiLock() {
        val wifiManager = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return
        val mode =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            } else {
                WifiManager.WIFI_MODE_FULL_HIGH_PERF
            }
        wifiLock =
            runCatching { wifiManager.createWifiLock(mode, "lemuroid:link").apply { acquire() } }
                .onFailure { Timber.w(it, "Unable to keep Wi-Fi awake") }
                .getOrNull()
    }

    private fun readRomBytes(romFiles: RomFiles): ByteArray {
        return when (romFiles) {
            is RomFiles.Standard -> romFiles.files.first().readBytes()
            is RomFiles.Virtual -> romFiles.files.first().fd.use { FileInputStream(it.fileDescriptor).readBytes() }
        }
    }

    private fun buildImmersiveModeConfiguration(enableImmersiveMode: Boolean): ImmersiveMode? {
        return if (enableImmersiveMode) {
            ImmersiveMode(blendFactor = 0.05f)
        } else {
            null
        }
    }

    private fun getLoadingMessage(loadingState: GameLoader.LoadingState): String {
        return when (loadingState) {
            is GameLoader.LoadingState.LoadingCore -> {
                appContext.getString(com.swordfish.lemuroid.ext.R.string.game_loading_download_core)
            }

            is GameLoader.LoadingState.LoadingGame -> {
                appContext.getString(R.string.game_loading_preparing_game)
            }

            else -> ""
        }
    }

    companion object {
        private const val VIRTUAL_INPUT_TAP_WINDOW_MS = 200L
        private const val LINKED_SCREENS_STACKED_ASPECT = 160f / 288f
        private const val LINKED_SCREENS_SIDE_BY_SIDE_ASPECT = 320f / 144f
    }

    private fun printRetroVariables(retroGameView: GLRetroView) {
        scope.launch {
            // Some cores do not immediately call SET_VARIABLES so we might need to wait a little bit
            delay(1.seconds)
            retroGameView.getVariables().forEach {
                Timber.i("Libretro variable: $it")
            }
        }
    }

    override fun onCreate(owner: LifecycleOwner) {
        super.onCreate(owner)

        owner.launchOnState(Lifecycle.State.STARTED) {
            initializeRetroGameViewErrorsFlow()
        }

        owner.launchOnState(Lifecycle.State.RESUMED) {
            initializeCoreVariablesFlow()
        }

        owner.launchOnState(Lifecycle.State.RESUMED) {
            initializeRumbleFlow()
        }
    }

    override fun onDestroy(owner: LifecycleOwner) {
        releaseNetLink()
        super.onDestroy(owner)
    }

    private suspend fun initializeCoreVariablesFlow() {
        try {
            waitRetroGameViewInitialized()
            val options = coreVariablesManager.getOptionsForCore(system.id, systemCoreConfig)
            updateCoreVariables(options)
        } catch (e: Exception) {
            Timber.e(e)
        }
    }

    private suspend fun initializeRumbleFlow() {
        val retroGameView = retroGameViewFlow()
        val rumbleEvents = retroGameView.getRumbleEvents()
        rumbleManager.collectAndProcessRumbleEvents(systemCoreConfig, rumbleEvents)
    }

    private suspend fun initializeRetroGameViewErrorsFlow() {
        retroGameViewFlow().getGLRetroErrors()
            .catch { Timber.e(it, "Exception in GLRetroErrors. Ironic.") }
            .collect { handleRetroViewError(it) }
    }

    private fun updateCoreVariables(options: List<CoreVariable>) {
        val retroGameView = retroGameView ?: return

        // This runs on every resume. Some cores react to any variable update (Gambatte restarts its
        // Game Link connection), so only send the variables when they actually changed.
        if (lastAppliedCoreVariables == (retroGameView to options)) return
        lastAppliedCoreVariables = retroGameView to options

        val updatedVariables =
            options.map { Variable(it.key, it.value) }
                .toTypedArray()

        updatedVariables.forEach {
            Timber.i("Updating core variable: ${it.key} ${it.value}")
        }

        retroGameView.updateVariables(*updatedVariables)
    }

    private fun handleRetroViewError(errorCode: Int) {
        Timber.e("Error in GLRetroView $errorCode")
        val gameLoaderError =
            when (errorCode) {
                GLRetroView.ERROR_GL_NOT_COMPATIBLE -> GameLoaderError.GLIncompatible
                GLRetroView.ERROR_LOAD_GAME -> GameLoaderError.LoadGame
                GLRetroView.ERROR_LOAD_LIBRARY -> GameLoaderError.LoadCore
                GLRetroView.ERROR_SERIALIZATION -> GameLoaderError.Saves
                else -> GameLoaderError.Generic
            }

        sideEffects.requestFailureFinish(getErrorMessage(gameLoaderError))
    }

    private fun getErrorMessage(gameError: GameLoaderError): String {
        val message =
            when (gameError) {
                is GameLoaderError.GLIncompatible -> {
                    appContext.getString(R.string.game_loader_error_gl_incompatible)
                }
                is GameLoaderError.Generic -> {
                    appContext.getString(R.string.game_loader_error_generic)
                }
                is GameLoaderError.LoadCore -> {
                    appContext.getString(com.swordfish.lemuroid.ext.R.string.game_loader_error_load_core)
                }
                is GameLoaderError.LoadGame -> {
                    appContext.getString(R.string.game_loader_error_load_game)
                }
                is GameLoaderError.Saves -> {
                    appContext.getString(R.string.game_loader_error_save)
                }
                is GameLoaderError.UnsupportedArchitecture -> {
                    appContext.getString(R.string.game_loader_error_unsupported_architecture)
                }
                is GameLoaderError.MissingBiosFiles -> {
                    appContext.getString(R.string.game_loader_error_missing_bios, gameError.missingFiles)
                }
            }

        return message
    }
}
