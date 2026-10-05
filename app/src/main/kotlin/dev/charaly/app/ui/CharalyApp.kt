package dev.charaly.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.charaly.app.ui.components.CharalyIconButton
import dev.charaly.app.ui.nav.Route
import dev.charaly.app.ui.nav.RouteHost
import dev.charaly.app.ui.nav.rememberNavigator
import dev.charaly.app.ui.nav.tabFor
import dev.charaly.app.ui.screens.CharacterDetailScreen
import dev.charaly.app.ui.screens.PackEditorTarget
import dev.charaly.app.ui.screens.CharacterEditorScreen
import dev.charaly.app.ui.screens.DeveloperLockedScreen
import dev.charaly.app.ui.screens.DeveloperScreen
import dev.charaly.app.ui.screens.EventEditorScreen
import dev.charaly.app.ui.screens.HomeScreen
import dev.charaly.app.ui.screens.LocationDetailScreen
import dev.charaly.app.ui.screens.LocationEditorScreen
import dev.charaly.app.ui.screens.ModelDetailScreen
import dev.charaly.app.ui.screens.ModelLibraryScreen
import dev.charaly.app.ui.screens.NewStoryScreen
import dev.charaly.app.ui.screens.OnboardingScreen
import dev.charaly.app.ui.screens.PackCreatorScreen
import dev.charaly.app.ui.screens.PackDetailScreen
import dev.charaly.app.ui.screens.SessionDetailScreen
import dev.charaly.app.ui.screens.SessionsScreen
import dev.charaly.app.ui.screens.SettingsScreen
import dev.charaly.app.ui.screens.StoryInstanceScreen
import dev.charaly.app.ui.screens.StoryPackLibraryScreen
import dev.charaly.app.ui.screens.WorldScreen
import dev.charaly.app.ui.theme.Charaly
import dev.charaly.app.ui.theme.motionDuration

/**
 * Charaly's navigation shell.
 *
 * Four bottom destinations: Home, Story Packs, Models, Sessions. Settings is not
 * one of them - it lives behind the avatar on Home, where a settings screen belongs
 * in a consumer app.
 *
 * The shell owns three things and nothing else: the back stack, the snackbar, and
 * the bottom bar. Every screen renders a snapshot the ViewModel computed.
 */
@Composable
fun CharalyApp(
    state: CharalyUiState,
    viewModel: CharalyViewModel,
    onExit: () -> Unit = {},
) {
    val navigator = rememberNavigator()
    val snackbar = remember { SnackbarHostState() }

    // System back: pop the stack, or leave the app from a root tab.
    BackHandler(enabled = true) {
        if (!navigator.pop()) onExit()
    }

    LaunchedEffect(state.notice, state.error) {
        val message = state.error ?: state.notice
        if (message != null) {
            snackbar.showSnackbar(message)
            viewModel.consumeNotice()
        }
    }

    // When a story is created, the wizard hands off to the immersive screen.
    LaunchedEffect(state.lastCreatedStoryId) {
        val created = state.lastCreatedStoryId
        if (created != null) {
            viewModel.consumeCreatedStory()
            viewModel.openStory(created)
            navigator.replaceAll(Route.Story(created))
        }
    }

    // First launch: three plain sentences about what this app is.
    if (!state.onboardingComplete) {
        OnboardingScreen(
            onFinish = viewModel::completeOnboarding,
        )
        return
    }

    // The story screen and the world screen are both immersive: no bottom bar while
    // you are inside a world, so nothing competes with the fiction.
    val showBottomBar = when (navigator.current) {
        is Route.Story, is Route.World -> false
        else -> true
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            // Duration from the motion policy, so "Reduce motion" also stops the bar
            // sliding rather than only stopping screen transitions.
            val barMs = motionDuration(200)
            AnimatedVisibility(
                visible = showBottomBar,
                enter = fadeIn(tween(barMs)) + slideInVertically(tween(barMs)) { it },
                exit = fadeOut(tween(barMs)) + slideOutVertically(tween(barMs)) { it },
            ) {
                CharalyBottomBar(
                    selected = navigator.current.tabFor(),
                    onSelect = { tab ->
                        navigator.selectTab(tab)
                    },
                )
            }
        },
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(bottom = padding.calculateBottomPadding()),
        ) {
            RouteHost(navigator = navigator, modifier = Modifier.fillMaxSize()) { route ->
                when (route) {
                    Route.Home -> HomeScreen(
                        snapshot = viewModel.homeSnapshot(),
                        loading = state.loading,
                        onOpenSettings = { navigator.navigateTo(Route.Settings) },
                        onOpenLibrary = { navigator.selectTab(Route.Tab.PACKS) },
                        onCreatePack = { navigator.navigateTo(Route.CreatePack) },
                        onContinueStory = { viewModel.openStory(it); navigator.replaceAll(Route.Story(it)) },
                        onOpenPack = { navigator.navigateTo(Route.PackDetail(it)) },
                        onOpenSession = { viewModel.openStory(it); navigator.replaceAll(Route.Story(it)) },
                        onOpenSessions = { navigator.selectTab(Route.Tab.SESSIONS) },
                        onOpenModels = { navigator.selectTab(Route.Tab.MODELS) },
                    )

                    Route.Library -> StoryPackLibraryScreen(
                        snapshot = viewModel.librarySnapshot(),
                        onQueryChange = viewModel::setLibraryQuery,
                        onToggleGenre = viewModel::toggleLibraryGenre,
                        onClearFilters = viewModel::clearLibraryFilters,
                        onSortChange = viewModel::setLibrarySort,
                        onOpenPack = { navigator.navigateTo(Route.PackDetail(it)) },
                        onCreatePack = { navigator.navigateTo(Route.CreatePack) },
                    )

                    Route.Models -> ModelLibraryScreen(
                        snapshot = viewModel.modelLibrarySnapshot(),
                        busy = state.modelBusy,
                        onQueryChange = viewModel::setModelQuery,
                        onImport = viewModel::importModel,
                        onOpenModel = { navigator.navigateTo(Route.ModelDetail(it)) },
                        onUseModel = { modelId -> viewModel.installedModel(modelId)?.let(viewModel::selectModel) },
                        onDeleteModel = { modelId -> viewModel.installedModel(modelId)?.let(viewModel::deleteModel) },
                        onVerifyModel = { modelId -> viewModel.installedModel(modelId)?.let(viewModel::verifyModel) },
                    )

                    Route.Sessions -> SessionsScreen(
                        snapshot = viewModel.sessionsSnapshot(),
                        onQueryChange = viewModel::setSessionsQuery,
                        onSortChange = viewModel::setSessionsSort,
                        onOpenSession = { navigator.navigateTo(Route.SessionDetail(it)) },
                        onContinue = { viewModel.openStory(it); navigator.replaceAll(Route.Story(it)) },
                        onRename = viewModel::renameSession,
                        onDelete = viewModel::deleteSession,
                        onBranch = viewModel::duplicateSession,
                        onExplorePacks = { navigator.selectTab(Route.Tab.PACKS) },
                    )

                    is Route.PackDetail -> PackDetailScreen(
                        snapshot = viewModel.packDetailSnapshot(route.packId),
                        loading = state.loading,
                        onBack = { navigator.pop() },
                        onContinue = { storyId ->
                            viewModel.openStory(storyId)
                            navigator.replaceAll(Route.Story(storyId))
                        },
                        onNewStory = { navigator.navigateTo(Route.NewStory(route.packId)) },
                        onOpenCharacter = { navigator.navigateTo(Route.CharacterDetail(route.packId, it)) },
                        onOpenLocation = { navigator.navigateTo(Route.LocationDetail(route.packId, it)) },
                        onOpenSession = { storyId ->
                            viewModel.openStory(storyId)
                            navigator.replaceAll(Route.Story(storyId))
                        },
                        onOpenModels = { navigator.selectTab(Route.Tab.MODELS) },
                        onEdit = { target, id ->
                            when (target) {
                                PackEditorTarget.CHARACTER -> navigator.navigateTo(
                                    Route.EditCharacter(route.packId, id),
                                )
                                PackEditorTarget.LOCATION -> navigator.navigateTo(
                                    Route.EditLocation(route.packId, id),
                                )
                                PackEditorTarget.EVENT -> navigator.navigateTo(
                                    Route.EditEvent(route.packId, id),
                                )
                            }
                        },
                    )

                    is Route.NewStory -> NewStoryScreen(
                        snapshot = viewModel.newStorySnapshot(),
                        loading = state.loading,
                        onBack = { navigator.pop() },
                        onStep = viewModel::newStoryStep,
                        onDraftChange = viewModel::updateDraft,
                        onEnterStory = viewModel::createStory,
                        onOpenModels = { navigator.selectTab(Route.Tab.MODELS) },
                    )

                    is Route.Story -> StoryInstanceScreen(
                        snapshot = viewModel.storySnapshot(),
                        loading = state.loading,
                        worldPanel = viewModel.worldPanelSnapshot(),
                        memoryPanel = viewModel.memoryPanelSnapshot(),
                        infoPanel = viewModel.storyInfoSnapshot(),
                        storyId = route.instanceId,
                        onBack = { navigator.pop() },
                        onSend = viewModel::send,
                        onStop = viewModel::stopGeneration,
                        onRegenerate = viewModel::regenerate,
                        onContinue = viewModel::continueGeneration,
                        onEditLast = viewModel::editLastUserMessage,
                        onSelectSpeaker = viewModel::selectSpeaker,
                        onAdvanceClock = viewModel::advanceClock,
                        onOpenModels = { navigator.selectTab(Route.Tab.MODELS) },
                        onRebind = viewModel::rebindStory,
                        onRetry = { navigator.navigateTo(Route.Sessions) },
                        onOpenWorld = { navigator.navigateTo(Route.World(route.instanceId)) },
                        installedModels = state.installedModels,
                        profiles = dev.charaly.runtime.model.ModelProfileLibrary.all,
                        engineLabel = viewModel.engineStatus().let { status ->
                            when (status) {
                                is EngineStatus.Ready -> status.name
                                is EngineStatus.Loading -> "Loading…"
                                is EngineStatus.Failed -> status.reason
                                EngineStatus.Unknown -> "Checking…"
                            }
                        },
                    )

                    is Route.World -> WorldScreen(
                        snapshot = viewModel.worldSnapshot(route.instanceId),
                        onBack = { navigator.pop() },
                        onEnterLocation = { locationId ->
                            viewModel.travelTo(locationId) {
                                // Entering a place opens a scene there, so walking in
                                // and starting to talk are one continuous action.
                                navigator.replaceAll(Route.Story(route.instanceId))
                            }
                        },
                        onOpenCharacter = { characterId ->
                            val packId = viewModel.storySnapshot()?.packId
                            if (packId != null) {
                                navigator.navigateTo(Route.CharacterDetail(packId, characterId))
                            }
                        },
                    )

                    is Route.SessionDetail -> SessionDetailScreen(
                        snapshot = viewModel.sessionsSnapshot(),
                        instanceId = route.instanceId,
                        info = viewModel.sessionInfo(route.instanceId),
                        lines = viewModel.sessionLines(route.instanceId),
                        onBack = { navigator.pop() },
                        onContinue = {
                            viewModel.openStory(route.instanceId)
                            navigator.replaceAll(Route.Story(route.instanceId))
                        },
                        onRename = { viewModel.renameSession(route.instanceId, it) },
                        onBranch = { viewModel.duplicateSession(route.instanceId) },
                        onDelete = {
                            viewModel.deleteSession(route.instanceId)
                            navigator.pop()
                        },
                        onExplorePacks = { navigator.selectTab(Route.Tab.PACKS) },
                    )

                    is Route.ModelDetail -> ModelDetailScreen(
                        snapshot = viewModel.modelDetailSnapshot(route.modelId),
                        onBack = { navigator.pop() },
                        onUse = { modelId ->
                            viewModel.installedModel(modelId)?.let(viewModel::selectModel)
                            navigator.pop()
                        },
                        onDelete = { modelId ->
                            viewModel.installedModel(modelId)?.let(viewModel::deleteModel)
                            navigator.pop()
                        },
                        onVerify = { modelId -> viewModel.installedModel(modelId)?.let(viewModel::verifyModel) },
                        onOpenStory = { storyId ->
                            viewModel.openStory(storyId)
                            navigator.replaceAll(Route.Story(storyId))
                        },
                    )

                    Route.Settings -> SettingsScreen(
                        state = state,
                        onBack = { navigator.pop() },
                        onSetDarkTheme = viewModel::setDarkTheme,
                        onSetReduceMotion = viewModel::setReduceMotion,
                        onSetDeveloperMode = viewModel::setDeveloperMode,
                        onOpenDeveloper = { navigator.navigateTo(Route.Developer) },
                        onOpenModels = { navigator.selectTab(Route.Tab.MODELS) },
                    )

                    // Developer mode is the gate, not a convention. It has to be re-checked *here*
                    // rather than only where the button is hidden, because a restored
                    // back stack can still contain this route: open the panel, switch
                    // developer mode off, restart - and without this check the panel
                    // would come back for a user who had explicitly turned it off.
                    Route.Developer -> if (state.developerMode) {
                        DeveloperScreen(
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
                        )
                    } else {
                        DeveloperLockedScreen(onBack = { navigator.pop() })
                    }

                    Route.CreatePack -> PackCreatorScreen(
                        packs = state.packs,
                        onBack = { navigator.pop() },
                        onSave = { pack ->
                            viewModel.saveCreatedPack(pack)
                            navigator.replaceAll(Route.PackDetail(pack.id.value))
                        },
                    )

                    is Route.CharacterDetail -> CharacterDetailScreen(
                        snapshot = viewModel.packDetailSnapshot(route.packId)?.characters
                            ?.firstOrNull { it.id == route.characterId },
                        packTitle = viewModel.packDetailSnapshot(route.packId)?.title.orEmpty(),
                        onBack = { navigator.pop() },
                    )

                    is Route.LocationDetail -> LocationDetailScreen(
                        snapshot = viewModel.packDetailSnapshot(route.packId)?.locations
                            ?.firstOrNull { it.id == route.locationId },
                        onBack = { navigator.pop() },
                    )

                    is Route.EditCharacter -> CharacterEditorScreen(
                        pack = state.packs.firstOrNull { it.id.value == route.packId },
                        characterId = route.characterId,
                        onBack = { navigator.pop() },
                        onSave = { character ->
                            viewModel.updateCharacter(route.packId, character)
                            navigator.pop()
                        },
                    )

                    is Route.EditLocation -> LocationEditorScreen(
                        pack = state.packs.firstOrNull { it.id.value == route.packId },
                        locationId = route.locationId,
                        onBack = { navigator.pop() },
                        onSave = { location ->
                            viewModel.updateLocation(route.packId, location)
                            navigator.pop()
                        },
                    )

                    is Route.EditEvent -> EventEditorScreen(
                        pack = state.packs.firstOrNull { it.id.value == route.packId },
                        eventId = route.eventId,
                        onBack = { navigator.pop() },
                        onSave = { event ->
                            viewModel.updateEvent(route.packId, event)
                            navigator.pop()
                        },
                    )
                }
            }
        }
    }
}

/**
 * The bottom navigation bar.
 *
 * Four destinations, always labelled. Labels beat icons alone for a library of
 * worlds, where "Story Packs" and "Sessions" are not obvious from a glyph.
 */
@Composable
private fun CharalyBottomBar(
    selected: Route.Tab,
    onSelect: (Route.Tab) -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
    ) {
        Column {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(Charaly.colors.hairline),
            )
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surface,
                tonalElevation = 0.dp,
                windowInsets = WindowInsets.navigationBars,
            ) {
                Route.Tab.entries.forEach { tab ->
                    NavigationBarItem(
                        selected = selected == tab,
                        onClick = { onSelect(tab) },
                        icon = {
                            Icon(
                                imageVector = tab.icon(),
                                contentDescription = null,
                                modifier = Modifier.size(22.dp),
                            )
                        },
                        label = {
                            Text(
                                text = tab.label,
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                            )
                        },
                        alwaysShowLabel = true,
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = Charaly.accent.primary,
                            selectedTextColor = Charaly.accent.primary,
                            indicatorColor = Charaly.accent.primary.copy(alpha = 0.14f),
                            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                        modifier = Modifier.semantics {
                            contentDescription = tab.label
                        },
                    )
                }
            }
        }
    }
}

private fun Route.Tab.icon(): ImageVector = when (this) {
    Route.Tab.HOME -> Icons.Filled.Home
    Route.Tab.PACKS -> Icons.Filled.AutoStories
    Route.Tab.MODELS -> Icons.Filled.Memory
    Route.Tab.SESSIONS -> Icons.Filled.History
}

/** A header row used by the top-level screens: title, optional subtitle, optional action. */
@Composable
fun ScreenHeader(
    title: String,
    subtitle: String = "",
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Charaly.tokens.spacing.gutter, vertical = Charaly.tokens.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (subtitle.isNotBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        action?.invoke()
    }
}

/** A circular avatar button used as the profile / settings entry point. */
@Composable
fun ProfileButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    label: String = "Settings",
) {
    Box(
        modifier = modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(Charaly.accent.primary.copy(alpha = 0.2f))
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "C",
            style = MaterialTheme.typography.labelLarge,
            color = Charaly.accent.primary,
        )
    }
}

/** Spacer row helper used by screens that need optical spacing between sections. */
@Composable
fun Gap(height: androidx.compose.ui.unit.Dp = 8.dp) {
    Spacer(Modifier.height(height))
}

@Composable
fun HGap(width: androidx.compose.ui.unit.Dp = 8.dp) {
    Spacer(Modifier.width(width))
}