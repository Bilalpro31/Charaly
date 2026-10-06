package dev.charaly.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import dev.charaly.app.ui.nav.CharalyNavigator
import dev.charaly.app.ui.nav.Route
import dev.charaly.app.ui.nav.RouteHost
import dev.charaly.app.ui.nav.destinationOrNull
import dev.charaly.app.ui.nav.rememberNavigator
import dev.charaly.app.ui.screens.EnterWorldScreen
import dev.charaly.app.ui.screens.HomeScreen
import dev.charaly.app.ui.screens.LibraryScreen
import dev.charaly.app.ui.screens.ModelDetailScreen
import dev.charaly.app.ui.screens.ModelHubScreen
import dev.charaly.app.ui.screens.SettingsScreen
import dev.charaly.app.ui.screens.ShowcaseScreen
import dev.charaly.app.ui.screens.StageSheet
import dev.charaly.app.ui.screens.StageScreen
import dev.charaly.app.ui.screens.StoryRecordScreen
import dev.charaly.app.ui.screens.WorldsScreen
import dev.charaly.app.ui.screens.heroHeightFor
import dev.charaly.app.ui.shell.CharalyScaffold
import dev.charaly.runtime.presentation.CharalyDestination
import dev.charaly.runtime.presentation.LayoutPolicy

/**
 * THE APPLICATION SHELL.
 *
 * ## What lives here
 *
 * The back stack, the snackbar, and the mapping from a route to a screen. Nothing else. A
 * shell that also formats data is a shell whose formatting cannot be tested.
 *
 * ## The rule that matters
 *
 * **Every destination in [Route] has exactly one screen here.** No destination renders a
 * second version of itself, and no screen is reachable by two routes with different
 * behaviour. That is asserted by a test which walks this file's `when`, because "no
 * duplicate destinations" is otherwise a thing a reviewer has to remember to check.
 *
 * ## Chat is the open story
 *
 * The CHAT destination resolves to whatever story is open, and to the most recent one when
 * nothing is. That is why entering a story does not need its own navigation flag: the
 * destination is "the stage", and the stage is a fact about the runtime rather than a field
 * in a back stack.
 */
@Composable
fun CharalyApp(
    state: CharalyUiState,
    viewModel: CharalyViewModel,
    onExit: () -> Unit = {},
    /**
     * Called after the language changes.
     *
     * The runtime presenters re-read the new language immediately, because `Loc` is a
     * process-wide catalogue rather than a resource. The Android resource strings need the
     * activity's configuration to change, which is what this triggers - and it is passed in
     * rather than reached for, so the shell stays free of an Activity reference.
     */
    onLanguageChanged: () -> Unit = {},
    /**
     * The process-scoped artwork cache.
     *
     * Passed in rather than reached for through a CompositionLocal: the shell is already
     * handed the view model and the state, and a third ambient source of app services would
     * make it impossible to render this tree with fakes in a test or a preview.
     *
     * Defaults to null, which every artwork consumer treats as "draw the deterministic
     * fallback" - so a preview renders the full UI with no assets at all.
     */
    storyAssets: dev.charaly.app.ui.art.StoryAssetLoader? = null,
) {
    val navigator = rememberNavigator()
    val snackbar = remember { SnackbarHostState() }

    // Drop decoded artwork when the system asks for memory. Not on navigation, and not on
    // every scene change: the cache exists so the second visit to a location is free, and
    // evicting it on the way out would defeat that. Under real pressure, keeping 8 MB of
    // background is the wrong trade - and llama.cpp's mmap is the thing actually needing
    // the room.
    val context = androidx.compose.ui.platform.LocalContext.current
    androidx.compose.runtime.DisposableEffect(context) {
        val component = context as? android.app.Activity
        val callback = object : android.content.ComponentCallbacks2 {
            override fun onConfigurationChanged(newConfig: android.content.res.Configuration) = Unit
            override fun onLowMemory() {
                storyAssets?.clear()
            }

            override fun onTrimMemory(level: Int) {
                if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
                    storyAssets?.clear()
                }
            }
        }
        component?.registerComponentCallbacks(callback)
        onDispose { component?.unregisterComponentCallbacks(callback) }
    }

    LaunchedEffect(state.notice, state.error) {
        val message = state.error ?: state.notice
        if (message != null) {
            snackbar.showSnackbar(message)
            viewModel.consumeNotice()
        }
    }

    // A newly created story takes over the stack. `replaceAll` rather than `navigateTo`,
    // because "back" from inside a story means "leave the story" - not "return to the
    // form I just filled in".
    LaunchedEffect(state.lastCreatedStoryId) {
        val created = state.lastCreatedStoryId
        if (created != null) {
            viewModel.consumeCreatedStory()
            viewModel.openStory(created)
            navigator.replaceAll(Route.Stage(created))
        }
    }

    if (!state.onboardingComplete) {
        dev.charaly.app.ui.screens.OnboardingScreen(onFinish = viewModel::completeOnboarding)
        return
    }

    // Layout is decided by width and nowhere else, so the tablet behaviour is a JVM test
    // rather than something to rediscover on a device.
    val widthDp = LocalConfiguration.current.screenWidthDp
    val policy = remember(widthDp) { LayoutPolicy.forWidth(widthDp) }

    // Whether the stage is actually on screen, resolved from the *runtime* rather than
    // from the route.
    //
    // This matters because CHAT and Stage are two routes to one screen. Tapping Chat with no
    // story open should show a navigable "nothing here yet"; the moment a story is open -
    // whether reached by tapping Chat or by opening one from the lobby - the stage owns the
    // screen and the navigation pill goes away, because the composer wants the bottom edge.
    // Reading this from `state.story` rather than from a route is what keeps both routes
    // behaving identically.
    val stageOnScreen = state.story != null
    val destination = when (val route = navigator.current) {
        Route.Chat -> if (stageOnScreen) null else CharalyDestination.CHAT
        else -> route.destinationOrNull()
    }

    // System back pops the stack, leaves the stage for the lobby, or leaves the app.
    //
    // `leaveImmersive` is what stops "back" from closing the app while the user is reading
    // a story: the stage is opened with `replaceAll`, so its stack is one deep and a plain
    // pop would find nothing to pop and hand control to the activity.
    BackHandler(enabled = true) {
        if (!navigator.leaveImmersive()) onExit()
    }

    CharalyScaffold(
        policy = policy,
        destination = destination,
        onSelect = navigator::select,
        snackbarHost = {
            SnackbarHost(
                snackbar,
                modifier = Modifier.windowInsetsPadding(WindowInsets.navigationBars),
            )
        },
    ) { screenModifier ->
        RouteHost(
            navigator = navigator,
            modifier = screenModifier,
            // Entering a world earns the longer transition. Nothing else does.
            cinematic = navigator.current is Route.Stage,
        ) { route ->
            when (route) {
                Route.Home -> HomeScreen(
                    snapshot = viewModel.lobbySnapshot(),
                    loading = state.loading,
                    policy = policy,
                    onContinue = { storyId -> openStory(viewModel, navigator, storyId) },
                    onOpenWorld = { packId -> navigator.navigateTo(Route.Showcase(packId)) },
                    onOpenStory = { storyId -> openStory(viewModel, navigator, storyId) },
                    onOpenWorlds = { navigator.selectTab(Route.Tab.WORLDS) },
                    onOpenModels = { navigator.navigateTo(Route.Models) },
                    onOpenSettings = { navigator.navigateTo(Route.Settings) },
                )

                Route.Worlds -> WorldsScreen(
                    snapshot = viewModel.worldsSnapshot(),
                    loading = state.loading,
                    policy = policy,
                    onQueryChange = viewModel::setWorldsQuery,
                    onToggleGenre = viewModel::toggleWorldsGenre,
                    onClearFilters = viewModel::clearWorldsFilters,
                    onOpenWorld = { packId -> navigator.navigateTo(Route.Showcase(packId)) },
                )

                // CHAT as a destination: the open story, or the most recent one, or an
                // honest empty state. Resolved from the runtime rather than from a flag.
                Route.Chat -> {
                    val mostRecent = state.instances
                        .maxByOrNull { it.sessionMeta.lastPlayedAtEpochMs }
                        ?.id?.value
                    if (state.story == null && mostRecent != null) {
                        LaunchedEffect(mostRecent) { viewModel.openStory(mostRecent) }
                    }
                    ChatRoute(
                        viewModel = viewModel,
                        state = state,
                        policy = policy,
                        navigator = navigator,
                        storyAssets = storyAssets,
                    )
                }

                is Route.Stage -> ChatRoute(
                    viewModel = viewModel,
                    state = state,
                    policy = policy,
                    navigator = navigator,
                    storyAssets = storyAssets,
                )

                Route.Library -> LibraryScreen(
                    shelf = viewModel.libraryShelf(),
                    loading = state.loading,
                    policy = policy,
                    onQueryChange = viewModel::setLibraryQuery,
                    onContinue = { storyId -> openStory(viewModel, navigator, storyId) },
                    onOpenStory = { storyId -> openStory(viewModel, navigator, storyId) },
                    onOpenDetails = { storyId -> navigator.navigateTo(Route.StoryRecord(storyId)) },
                    onOpenWorlds = { navigator.selectTab(Route.Tab.WORLDS) },
                )

                is Route.Showcase -> {
                    val showcase = viewModel.showcase(route.packId)
                    ShowcaseScreen(
                        showcase = showcase,
                        resumable = showcase?.let { viewModel.resumableStory(route.packId) },
                        loading = state.loading,
                        heroHeight = heroHeightFor(showcase?.heroTreatment ?: dev.charaly.runtime.domain.HeroTreatment.FULL_BLEED),
                        onBack = { navigator.pop() },
                        onEnterWorld = {
                            viewModel.beginNewStory(route.packId)
                            navigator.navigateTo(Route.EnterWorld(route.packId))
                        },
                        onContinueStory = { storyId -> openStory(viewModel, navigator, storyId) },
                        onAboutThisWorld = { showcase?.let { viewModel.announce(it.title) } },
                    )
                }

                is Route.EnterWorld -> {
                    // The route survives process death; the draft now does too. This is the
                    // seam: whichever of the two is missing is rebuilt before the wizard is
                    // composed, so "no draft" can never render as "no Continue button".
                    LaunchedEffect(route.packId) { viewModel.ensureNewStoryDraft(route.packId) }
                    val cardPicker = rememberLauncherForActivityResult(
                        contract = ActivityResultContracts.OpenDocument(),
                        onResult = { uri -> uri?.let(viewModel::readCharacterCard) },
                    )
                    EnterWorldScreen(
                        snapshot = viewModel.enterWorldSnapshot(),
                        loading = state.loading,
                        // Backing out of the wizard abandons the draft. The model registry is
                        // untouched, so an import made from here survives; only the half-built
                        // story setup is discarded, and a later visit starts clean.
                        onBack = {
                            viewModel.cancelNewStory()
                            navigator.pop()
                        },
                        onStep = viewModel::newStoryStep,
                        onSelectScenario = { id ->
                            viewModel.updateDraft { draft -> draft.copy(scenarioId = id) }
                        },
                        onSelectPersona = { id ->
                            viewModel.updateDraft { draft -> draft.copy(personaId = id) }
                        },
                        onSelectCast = { id, selected ->
                            viewModel.updateDraft { draft ->
                                draft.copy(
                                    castCharacterIds = if (selected) {
                                        draft.castCharacterIds + id
                                    } else {
                                        draft.castCharacterIds - id
                                    },
                                )
                            }
                        },
                        // The user's own characters, kept in their own draft field so a pack
                        // character and an imported one can never be confused for each other.
                        onToggleImported = { id, selected ->
                            viewModel.updateDraft { draft ->
                                draft.copy(
                                    importedCharacterIds = if (selected) {
                                        draft.importedCharacterIds + id
                                    } else {
                                        draft.importedCharacterIds - id
                                    },
                                )
                            }
                        },
                        // SAF: no storage permission is requested. `image/png` and `json`
                        // cover both shapes a real card comes in, and the importer sniffs the
                        // bytes anyway, so a mis-typed mime type is not a dead end.
                        // Reached from the cast step: the picker runs inline there so the draft
                        // survives, and the preview appears as a sheet over the wizard.
                        onImportCharacter = {
                            cardPicker.launch(arrayOf("image/png", "application/json", "text/json", "*/*"))
                        },
                        onOpenModels = { navigator.navigateTo(Route.Models) },
                        onEnter = { viewModel.createStory(route.packId) },
                    )
                }

                Route.Models -> {
                    val snapshot = viewModel.modelHubSnapshot()
                    val picker = rememberLauncherForActivityResult(
                        contract = ActivityResultContracts.OpenDocument(),
                        onResult = { uri -> uri?.let(viewModel::importModel) },
                    )
                    ModelHubScreen(
                        /**
                         * Shown only while a story setup is waiting.
                         *
                         * Reaching Models from the story's "Change the voice" control used to
                         * be a one-way trip: that route hides the navigation bar and has no
                         * back control, so a user who went to import a GGUF had no visible
                         * way to return to the story they were building. This is that way
                         * back, and it is the specific bug - a missing Continue - reduced to
                         * the control that should have been there.
                         */
                        onBackToStory = if (viewModel.hasPendingStorySetup()) {
                            { navigator.pop() }
                        } else {
                            null
                        },
                        hero = snapshot.hero,
                        cards = snapshot.cards,
                        filter = state.modelFilter,
                        query = state.hubQuery,
                        transfer = viewModel.transferProgress(),
                        hubState = state.hubState,
                        repoDetail = state.hubDetail,
                        detailState = state.hubDetailState,
                        isOnline = state.isOnline,
                        busy = state.modelBusy,
                        importState = state.modelImport,
                        hasInstalledModels = snapshot.installed.isNotEmpty(),
                        onFilterChange = viewModel::setModelFilter,
                        onQueryChange = viewModel::setHubQuery,
                        onOpenModel = { modelId -> navigator.navigateTo(Route.ModelDetail(modelId)) },
                        onUseModel = { modelId -> viewModel.selectModelById(modelId) },
                        onDeleteModel = { modelId -> viewModel.deleteModelById(modelId) },
                        // A real measurement, or nothing. The card's Measure control runs
                        // the model; there is no path that produces a number any other way.
                        onBenchmarkModel = viewModel::benchmarkModel,
                        speedSummary = snapshot.speedSummary,
                        benchmarkFailureMessage = state.benchmarkFailureMessage,
                        onOpenRepo = viewModel::openHubRepo,
                        onCloseRepo = viewModel::closeHubRepo,
                        onDownload = viewModel::startHubDownload,
                        onPauseDownload = viewModel::pauseDownload,
                        onResumeDownload = viewModel::resumeDownload,
                        onCancelDownload = viewModel::cancelDownload,
                        onImport = { picker.launch(arrayOf("*/*")) },
                        onRetrySearch = { viewModel.setHubQuery(state.hubQuery) },
                    )
                }

                is Route.ModelDetail -> ModelDetailScreen(
                    snapshot = viewModel.modelDetail(route.modelId),
                    loading = state.loading,
                    onBack = { navigator.pop() },
                    onUse = { modelId ->
                        viewModel.selectModelById(modelId)
                        navigator.pop()
                    },
                    onDelete = { modelId ->
                        viewModel.deleteModelById(modelId)
                        navigator.pop()
                    },
                    onVerify = { modelId -> viewModel.verifyModelById(modelId) },
                    onOpenStory = { storyId -> openStory(viewModel, navigator, storyId) },
                )

                is Route.StoryRecord -> {
                    val record = viewModel.storyRecord(route.instanceId)
                    StoryRecordScreen(
                        title = record?.title.orEmpty(),
                        moment = record?.moment.orEmpty(),
                        contextLine = record?.contextLine.orEmpty(),
                        beats = record?.beats.orEmpty(),
                        chapters = record?.chapters.orEmpty(),
                        onBack = { navigator.pop() },
                        onContinue = {
                            viewModel.openStory(route.instanceId)
                            navigator.replaceAll(Route.Stage(route.instanceId))
                        },
                        onDelete = {
                            viewModel.deleteSession(route.instanceId)
                            navigator.popToRoot()
                        },
                    )
                }

                Route.CharacterImport -> {
                    val cardPicker = rememberLauncherForActivityResult(
                        contract = ActivityResultContracts.OpenDocument(),
                        onResult = { uri -> uri?.let(viewModel::readCharacterCard) },
                    )
                    dev.charaly.app.ui.screens.CharacterImportScreen(
                        snapshot = state.characterImport,
                        onBack = { navigator.pop() },
                        // The import screen reuses this same picker, so the flow can be re-entered from
                    // the screen itself after a file turns out to be the wrong one.
                    onPick = { cardPicker.launch(arrayOf("image/png", "application/json", "text/json", "*/*")) },
                        onConfirm = viewModel::confirmCharacterImport,
                        onCancel = viewModel::cancelCharacterImport,
                        onDone = { navigator.pop() },
                    )
                }

                Route.Settings -> SettingsScreen(
                    darkTheme = state.darkTheme,
                    reduceMotion = state.reduceMotion,
                    developerMode = state.developerMode,
                    // The one authoritative selection. `loadedModelId` said "no model" for
                    // an imported GGUF that was merely still loading.
                    modelReady = state.model.canGenerate,
                    modelState = dev.charaly.runtime.presentation.ModelStagePresenter
                        .stateLabel(state.model),
                    modelDetail = dev.charaly.runtime.presentation.ModelStagePresenter
                        .reason(state.model),
                    model = state.model,
                    languageTag = state.languageTag,
                    // The one control that actually runs the engine, reachable without
                    // Developer Mode.
                    modelTest = state.modelTest,
                    modelTestSubjectName = viewModel.modelTestSubject()?.displayName.orEmpty(),
                    onRunModelTest = viewModel::runModelTest,
                    // With nothing installed the test cannot run, so the control has to
                    // lead somewhere rather than be disabled.
                    onOpenModels = { navigator.navigateTo(Route.Models) },
                    onClearModelTest = viewModel::clearModelTest,
                    activeModelName = viewModel.currentModelName(),
                    installedModelCount = state.installedModels.size,
                    storyCount = state.instances.size,
                    installedBytes = state.installedModels.sumOf { it.sizeBytes },
                    versionName = appVersionName(LocalContext.current),
                    onBack = { navigator.pop() },
                    onOpenAuthoring = { navigator.navigateTo(Route.Authoring) },
                    onSetDarkTheme = viewModel::setDarkTheme,
                    onSetReduceMotion = viewModel::setReduceMotion,
                    onSetDeveloperMode = viewModel::setDeveloperMode,
                    onSetLanguage = { tag ->
                        viewModel.setLanguage(tag)
                        onLanguageChanged()
                    },
                    onOpenDeveloper = {
                        if (state.developerMode) {
                            navigator.navigateTo(Route.Developer)
                        }
                    },
                )

                // Developer Mode is the gate, not a convention.
                //
                // Re-checked *here* rather than only where the button is hidden, because a
                // restored back stack can still contain this route: open the panel, turn
                // developer mode off, restart - and without this check the inspector would
                // come back for a user who had explicitly turned it off.
                Route.Developer -> if (state.developerMode) {
                    dev.charaly.app.ui.screens.DeveloperScreen(
                        onBack = { navigator.pop() },
                        worldState = viewModel.worldStateJson(),
                        contextPreview = viewModel.contextPreview(),
                        contextSections = viewModel.contextSectionBreakdown(),
                        promptEstimate = viewModel.promptEstimate(),
                        modelDiagnostics = viewModel.modelDiagnostics(),
                        eventLog = viewModel.eventLog(),
                        storyHealth = viewModel.storyHealth(),
                        storyHealthStatus = viewModel.storyHealthStatus(),
                        causality = viewModel.causalityGraph(),
                        threads = viewModel.storyThreads(),
                        minds = viewModel.characterMinds(),
                        commitments = viewModel.commitments(),
                        stories = state.instances.map { it.id.value },
                        instanceId = state.story?.id?.value.orEmpty(),
                        // The clock and the per-turn cost, both read from the runtime rather
                        // than recomputed here, so the panel cannot disagree with the engine.
                        clockLine = viewModel.worldClockLine(),
                        turnCostLabel = viewModel.turnCostLabel(),
                        // The same `advance` a real turn takes, so this exercises the event
                        // engine rather than setting a field behind its back.
                        onAdvanceTime = viewModel::advanceClock,
                    )
                } else {
                    dev.charaly.app.ui.screens.DeveloperLockedScreen(
                        onBack = { navigator.pop() },
                    )
                }

                // Authoring is the one tool in the app rather than a place to go, so it
                // lives behind Settings and not on the navigation bar.
                Route.Authoring -> dev.charaly.app.ui.screens.AuthoringScreen(
                    packs = state.packs,
                    onBack = { navigator.pop() },
                    onSave = { pack ->
                        viewModel.saveCreatedPack(pack)
                        navigator.replaceAll(Route.Worlds)
                    },
                )
            }
        }
    }
}

/**
 * The stage, reached either as the CHAT destination or as a specific story.
 *
 * One composable for both routes, so "open a story from the library" and "tap Chat" render
 * the same screen with the same behaviour. Two routes to one screen is fine; two *versions*
 * of one screen is not.
 */
@Composable
private fun ChatRoute(
    viewModel: CharalyViewModel,
    state: CharalyUiState,
    policy: LayoutPolicy,
    navigator: CharalyNavigator,
    storyAssets: dev.charaly.app.ui.art.StoryAssetLoader?,
) {
    var sheet by remember { mutableStateOf<StageSheet?>(null) }

    StageScreen(
        stage = viewModel.stageSnapshot(),
        loading = state.loading,
        policy = policy,
        sheet = sheet,
        context = viewModel.stageContext(),
        // The same rule as system back: leaving the stage, not closing the app. A user who
        // taps the stage's own back arrow is telling us they want out of the story, and
        // that must never mean "Charaly has closed".
        onBack = { navigator.leaveImmersive() },
        onSend = viewModel::send,
        onStop = viewModel::stopGeneration,
        onOpenSheet = { sheet = it },
        onDismissSheet = { sheet = null },
        onOpenModels = {
            sheet = null
            navigator.navigateTo(Route.Models)
        },
        onRetry = viewModel::retryGeneration,
        // The process-scoped artwork cache, so a screen change does not re-decode a
        // background the user has already looked at.
        assetLoader = storyAssets,
        onSelectSpeaker = { id ->
            viewModel.selectSpeaker(dev.charaly.runtime.domain.CharacterId(id))
            sheet = null
        },
    )
}

/**
 * Opening a story.
 *
 * One function, because three call sites used to do this slightly differently - and the one
 * that forgot to load the story produced a stage with no transcript, which is the kind of
 * bug that looks like the engine failing.
 */
private fun openStory(viewModel: CharalyViewModel, navigator: CharalyNavigator, storyId: String) {
    viewModel.openStory(storyId)
    navigator.replaceAll(Route.Stage(storyId))
}
