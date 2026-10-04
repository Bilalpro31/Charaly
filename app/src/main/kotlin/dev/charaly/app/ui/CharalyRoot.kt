package dev.charaly.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.charaly.app.model.ModelEntry
import dev.charaly.runtime.domain.StoryInstance
import dev.charaly.runtime.domain.TranscriptEntry
import dev.charaly.runtime.domain.TranscriptRole

private enum class Tab(val label: String) {
    Stories("Stories"),
    Scene("Scene"),
    Models("Models"),
    Inspector("Inspector"),
}

/**
 * Charaly's UI: four screens, all of them local.
 *
 * Stories -> Story selection -> Story instance -> Scene/chat -> Character interaction,
 * plus accessible model controls and a runtime inspector.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CharalyRoot(
    state: CharalyUiState,
    viewModel: CharalyViewModel,
) {
    var tab by remember { mutableIntStateOf(Tab.Stories.ordinal) }
    val snackbar = remember { SnackbarHostState() }

    // Importing uses the Storage Access Framework: a per-file grant, no broad
    // storage permission, no network.
    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
        onResult = { uri -> uri?.let(viewModel::importModel) },
    )

    LaunchedEffect(state.notice, state.error) {
        val message = state.error ?: state.notice
        if (message != null) {
            snackbar.showSnackbar(message)
            viewModel.consumeNotice()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Charaly", fontWeight = FontWeight.SemiBold)
                        Text(
                            "Local-first story runtime",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = viewModel::refresh) {
                        Icon(Icons.Default.Refresh, contentDescription = "Reload stories")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            EngineBanner(state.engineStatus)

            TabRow(selectedTabIndex = tab) {
                Tab.entries.forEach { entry ->
                    Tab(
                        selected = tab == entry.ordinal,
                        onClick = { tab = entry.ordinal },
                        text = { Text(entry.label) },
                    )
                }
            }

            when (Tab.entries[tab]) {
                Tab.Stories -> StoriesScreen(state, viewModel)
                Tab.Scene -> SceneScreen(state, viewModel)
                Tab.Models -> ModelsScreen(
                    state = state,
                    onImport = { importLauncher.launch(arrayOf("*/*")) },
                    onSelect = viewModel::selectModel,
                    onDelete = viewModel::deleteModel,
                )
                Tab.Inspector -> InspectorScreen(state, viewModel)
            }
        }
    }
}

@Composable
private fun EngineBanner(status: EngineStatus) {
    val (label, tint) = when (status) {
        is EngineStatus.Ready -> "${status.name} (local)" to MaterialTheme.colorScheme.secondary
        is EngineStatus.Loading -> "Loading model..." to MaterialTheme.colorScheme.primary
        is EngineStatus.Failed -> status.reason to MaterialTheme.colorScheme.error
        EngineStatus.Unknown -> "Checking local model..." to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(color = tint.copy(alpha = 0.12f), modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (status is EngineStatus.Loading) {
                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                Box(Modifier.width(8.dp))
            }
            Text(label, style = MaterialTheme.typography.labelMedium)
        }
    }
}

// ---------------------------------------------------------------------------
// Stories
// ---------------------------------------------------------------------------

@Composable
private fun StoriesScreen(state: CharalyUiState, viewModel: CharalyViewModel) {
    if (state.loading) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(
                "Choose a story. Everything runs on this device.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        items(state.packs, key = { it.id.value }) { pack ->
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(pack.title, style = MaterialTheme.typography.titleMedium)
                    if (pack.description.isNotBlank()) {
                        Text(pack.description, style = MaterialTheme.typography.bodySmall)
                    }
                    Text(
                        "${pack.characters.size} characters - ${pack.locations.size} locations",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { viewModel.startOrContinue(pack) }) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null, Modifier.size(18.dp))
                            Box(Modifier.width(4.dp))
                            Text(if (state.instances.any { it.storyPackId == pack.id }) "Continue" else "Start")
                        }
                        OutlinedButton(onClick = viewModel::refresh, enabled = false) { Text("Open") }
                    }
                }
            }
        }

        if (state.instances.isNotEmpty()) {
            item { HorizontalDivider() }
            item {
                Text("Saved stories", style = MaterialTheme.typography.titleSmall)
            }
            items(state.instances, key = { it.id.value }) { instance ->
                SavedStoryRow(instance, viewModel::openStory)
            }
        }
    }
}

@Composable
private fun SavedStoryRow(instance: StoryInstance, onOpen: (StoryInstance) -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(instance.packTitle, style = MaterialTheme.typography.bodyLarge)
                Text(
                    "${instance.worldClock.now.format()} - ${instance.conversation.size} lines - " +
                        "${instance.memories.size} memories",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = { onOpen(instance) }) { Text("Resume") }
        }
    }
}

// ---------------------------------------------------------------------------
// Scene / chat
// ---------------------------------------------------------------------------

@Composable
private fun SceneScreen(state: CharalyUiState, viewModel: CharalyViewModel) {
    val story = state.story
    if (story == null) {
        EmptyHint("Start a story from the Stories tab.")
        return
    }

    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(state.transcript.size, state.pendingReply) {
        val target = state.transcript.size + if (state.pendingReply.isNotEmpty()) 1 else 0
        if (target > 0) listState.animateScrollToItem(target - 1)
    }

    Column(Modifier.fillMaxSize()) {
        CharacterSelector(state, viewModel)

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(state.transcript, key = { it.id }) { entry ->
                TranscriptBubble(entry)
            }
            if (state.pendingReply.isNotEmpty()) {
                item(key = "pending") {
                    TranscriptBubble(
                        TranscriptEntry(
                            id = "pending",
                            turn = -1,
                            role = TranscriptRole.CHARACTER,
                            text = state.pendingReply,
                            at = story.worldClock.now,
                            speakerId = state.focusCharacterId,
                        ),
                        streaming = true,
                    )
                }
            }
        }

        HorizontalDivider()

        Row(
            Modifier.padding(12.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                enabled = !state.generating,
                placeholder = { Text("Say or do something...") },
                maxLines = 4,
                shape = RoundedCornerShape(12.dp),
            )
            if (state.generating) {
                IconButton(onClick = viewModel::stopGeneration) {
                    Icon(Icons.Default.Stop, contentDescription = "Stop generating")
                }
            } else {
                Button(
                    onClick = {
                        viewModel.send(input)
                        input = ""
                    },
                    enabled = state.canGenerate && input.isNotBlank(),
                ) { Text("Send") }
            }
        }
    }
}

@Composable
private fun CharacterSelector(state: CharalyUiState, viewModel: CharalyViewModel) {
    val story = state.story ?: return
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Talking to", style = MaterialTheme.typography.labelMedium)
            story.worldState.characters.values
                .sortedBy { it.characterId.value }
                .forEach { character ->
                    AssistChip(
                        onClick = { viewModel.selectCharacter(character.characterId) },
                        label = { Text(character.name) },
                    )
                }
        }
    }
}

@Composable
private fun TranscriptBubble(entry: TranscriptEntry, streaming: Boolean = false) {
    // A full Box alignment (not Horizontal) so user and character lines sit on
    // opposite sides of the same list.
    val alignment: Alignment = if (entry.role == TranscriptRole.USER) {
        Alignment.CenterEnd
    } else {
        Alignment.CenterStart
    }
    val container = when (entry.role) {
        TranscriptRole.USER -> MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
        TranscriptRole.NARRATION -> MaterialTheme.colorScheme.surfaceVariant
        else -> MaterialTheme.colorScheme.secondary.copy(alpha = 0.16f)
    }
    Box(Modifier.fillMaxWidth(), contentAlignment = alignment) {
        Card(
            colors = CardDefaults.cardColors(containerColor = container),
            modifier = Modifier.widthIn(max = 340.dp),
        ) {
            Column(Modifier.padding(12.dp)) {
                Text(
                    buildString {
                        append(entry.speakerId?.value ?: entry.role.name.lowercase())
                        if (streaming) append(" ...")
                    },
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(entry.text, style = MaterialTheme.typography.bodyMedium)
                Text(
                    entry.at.format(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Models
// ---------------------------------------------------------------------------

@Composable
private fun ModelsScreen(
    state: CharalyUiState,
    onImport: () -> Unit,
    onSelect: (ModelEntry) -> Unit,
    onDelete: (ModelEntry) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Local models", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Charaly never downloads a model. Import a GGUF file from this device; " +
                            "it is copied into app storage and loaded by llama.cpp inside the app. " +
                            "Inference then works with no network at all.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Button(onClick = onImport) {
                        Icon(Icons.Default.Add, contentDescription = null, Modifier.size(18.dp))
                        Box(Modifier.width(6.dp))
                        Text("Import GGUF")
                    }
                }
            }
        }

        if (state.models.isEmpty()) {
            item {
                Text(
                    "No models imported yet. A small instruct-tuned Q4 GGUF (1-4B) is a good start on a phone.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        items(state.models, key = { it.absolutePath }) { model ->
            val selected = state.engineStatus is EngineStatus.Ready &&
                (state.engineStatus as EngineStatus.Ready).name == model.displayName
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(model.displayName, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "${model.sizeLabel()} - ${model.absolutePath}",
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = { onSelect(model) }) {
                        Text(if (selected) "Loaded" else "Load")
                    }
                    IconButton(onClick = { onDelete(model) }) {
                        Icon(Icons.Default.Delete, contentDescription = "Delete ${model.displayName}")
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Inspector (developer view of the deterministic runtime)
// ---------------------------------------------------------------------------

@Composable
private fun InspectorScreen(state: CharalyUiState, viewModel: CharalyViewModel) {
    val story = state.story
    if (story == null) {
        EmptyHint("Start a story to inspect its world state.")
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { InspectorCard("World clock") { InspectorText("${story.worldClock.now.format()} (rev ${story.worldState.revision})") } }
        item { InspectorCard("Current scene") { InspectorText(story.currentScene()?.describe() ?: "no active scene") } }

        item {
            InspectorCard("Characters") {
                InspectorText(
                    story.worldState.characters.values.sortedBy { it.characterId.value }.joinToString("\n") { character ->
                        "${character.name} @ ${character.locationId?.value ?: "?"} " +
                            "[${character.activity.name}] facts=${character.knownFactIds.size} " +
                            "memories=${character.memoryIds.size}"
                    },
                )
            }
        }

        item {
            InspectorCard("Relationships") {
                InspectorText(
                    if (story.relationships.isEmpty()) {
                        "none recorded"
                    } else {
                        story.relationships.values.sortedBy { it.sourceId.value }.joinToString("\n") { rel ->
                            "${rel.sourceId.value} -> ${rel.targetId.value}: ${rel.describe(rel.sourceId)}"
                        }
                    },
                )
            }
        }

        item {
            InspectorCard("Knowledge (who knows what)") {
                InspectorText(
                    if (story.knowledge.factCount == 0) {
                        "no world facts"
                    } else {
                        story.knowledge.knowledge.entries.sortedBy { it.key.value }.joinToString("\n") { (who, entries) ->
                            "$who: " + entries.mapNotNull { story.knowledge.fact(it.factId)?.render() }.joinToString(" | ")
                        }
                    },
                )
            }
        }

        item {
            InspectorCard("Pending events") {
                InspectorText(
                    if (story.eventQueue.isEmpty()) {
                        "queue empty"
                    } else {
                        story.eventQueue.snapshot().joinToString("\n") { "${it.scheduledAt.format()} ${it.describe()}" }
                    },
                )
            }
        }

        item {
            InspectorCard("Story threads") {
                InspectorText(
                    story.storyThreads.values.sortedBy { it.id.value }.joinToString("\n") { thread ->
                        "${thread.title} [${thread.status.name}] stage ${thread.stage}"
                    },
                )
            }
        }

        item {
            InspectorCard("Advance the clock (deterministic)") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { viewModel.advanceClock(10) }) { Text("+10 min") }
                    OutlinedButton(onClick = { viewModel.advanceClock(60) }) { Text("+1 hour") }
                    OutlinedButton(onClick = { viewModel.advanceClock(60 * 8) }) { Text("+8 hours") }
                }
            }
        }
    }
}

@Composable
private fun InspectorCard(title: String, content: @Composable () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Box(Modifier.padding(top = 6.dp).heightIn(min = 20.dp)) { content() }
        }
    }
}

@Composable
private fun InspectorText(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        fontFamily = FontFamily.Monospace,
        textAlign = TextAlign.Start,
    )
}

@Composable
private fun EmptyHint(message: String) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}
