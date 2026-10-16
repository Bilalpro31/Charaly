package dev.charaly.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.charaly.app.ui.components.CharalyAction
import dev.charaly.app.ui.components.CharalyIconButton
import dev.charaly.app.ui.components.CharalyPill
import dev.charaly.app.ui.components.CharalyQuietAction
import dev.charaly.app.ui.components.CharalySectionHeader
import dev.charaly.app.ui.design.Charaly
import dev.charaly.app.ui.design.CharalyShapes
import dev.charaly.app.ui.packstore.AuthoringCompiler
import dev.charaly.app.ui.packstore.AuthoringDraft
import dev.charaly.app.ui.packstore.BranchDraft
import dev.charaly.app.ui.packstore.CharacterDraft
import dev.charaly.app.ui.packstore.EffectDraft
import dev.charaly.app.ui.packstore.EffectKind
import dev.charaly.app.ui.packstore.EventDraft
import dev.charaly.app.ui.packstore.IssueRow
import dev.charaly.app.ui.packstore.LoreDraft
import dev.charaly.app.ui.packstore.LocationDraft
import dev.charaly.app.ui.packstore.ObjectiveDraft
import dev.charaly.app.ui.packstore.PackImportReportUi
import dev.charaly.app.ui.packstore.PersonaDraft
import dev.charaly.app.ui.packstore.RelationshipDraft
import dev.charaly.app.ui.packstore.ScenarioDraft
import dev.charaly.app.ui.packstore.SceneDraft
import dev.charaly.app.ui.packstore.TextDraft
import dev.charaly.app.ui.packstore.ThreadDraft
import dev.charaly.app.ui.packstore.newRowId
import dev.charaly.app.ui.packstore.packVersionLabel
import dev.charaly.app.ui.packstore.toImportReportUi
import dev.charaly.runtime.domain.CharacterRole
import dev.charaly.runtime.domain.StoryPack

/**
 * AUTHORING: a six-step wizard that ends in a real `.charaly-pack`.
 *
 * ## The one part of Charaly that is a tool
 *
 * Everything else is a place you go. This is a form you fill in - deliberately styled like
 * one: labelled fields, one step at a time, a real review, explicit export.
 *
 * ## What it creates, and what it does not
 *
 * A [StoryPack]: people, places, rules, scenes and branches, compiled by
 * [AuthoringCompiler] into the same domain objects the runtime plays. Nothing here is a
 * placeholder - the "Gelen kisi" pack of the old stub is gone, and a wizard that collects
 * two dozen fields would be a liar if it threw them away. What the wizard still does not
 * create is a *story*: installing the pack lands it in the Library, and stepping into it is
 * a separate decision made through the normal flow.
 *
 * ## Why the draft is local state
 *
 * Because the draft is the screen - six steps editing one value - and the only thing the
 * rest of the app needs is the finished pack. Handing the view model a half-typed field on
 * every keystroke would drag a 3500-line class into a workflow it has no opinion about;
 * one call at the end (`onSave` / `onExportFile`) is the whole contract.
 *
 * ## Why step 5 exists
 *
 * Because the validators know things the form cannot show: a branch that changes nothing,
 * an objective that can never complete, a scenario opening into a deleted scene. They are
 * the *same* validators the installer runs on an imported archive, so "Kuruluma hazir" here
 * and "Yukle" there cannot disagree.
 */
@Composable
fun AuthoringScreen(
    packs: List<StoryPack>,
    onBack: () -> Unit,
    onSave: (StoryPack) -> Unit,
    onExportFile: (StoryPack) -> Unit,
) {
    var draft by remember { mutableStateOf(AuthoringDraft()) }
    var step by remember { mutableStateOf(AuthoringStep.KIMLIK) }

    // The compiled pack and its report exist only on the two steps that read them.
    // Compiling on every keystroke of a field would work and would be waste; the wizard is
    // a form, and forms are reviewed at the end.
    val compiled = if (draft.hasTitle && step.readsCompilation) {
        remember(draft) { AuthoringCompiler.compile(draft) }
    } else {
        null
    }
    val report = compiled?.let { pack -> remember(pack) { AuthoringCompiler.review(pack).toImportReportUi() } }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Charaly.surface.base),
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = Charaly.space.gutter,
                end = Charaly.space.gutter,
                top = Charaly.space.xxl,
                bottom = 140.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(Charaly.space.md),
        ) {
            item(key = "wizard-top") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CharalyIconButton(
                        icon = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Sihirbazdan çık",
                        onClick = onBack,
                    )
                    Spacer(Modifier.size(Charaly.space.xs))
                    Text(
                        text = "Adım ${step.number} · ${step.title}",
                        style = MaterialTheme.typography.headlineLarge,
                        color = Charaly.ink.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.semantics { heading() },
                    )
                }
            }

            item(key = "wizard-steps") { AuthoringStepBar(step = step) }

            when (step) {
                AuthoringStep.KIMLIK -> identityStep(draft) { draft = it }
                AuthoringStep.DUNYA -> worldStep(draft, onDraftChange = { draft = it })
                AuthoringStep.KADRO -> castStep(draft, onDraftChange = { draft = it })
                AuthoringStep.HIKAYE -> storyStep(draft, onDraftChange = { draft = it })
                AuthoringStep.KONTROL -> reviewStep(compiled, report)
                AuthoringStep.AKTAR -> exportStep(
                    compiled = compiled,
                    report = report,
                    existingIds = packs.map { it.id.value },
                    onSave = onSave,
                    onExportFile = onExportFile,
                )
            }
        }

        // The step actions, pinned. One row, always in the same place, so a six-step form
        // has a rhythm instead of a hunt for the button - the same idiom as the story setup
        // wizard, for the same reason.
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Charaly.surface.void)
                .navigationBarsPadding()
                .padding(
                    start = Charaly.space.gutter,
                    end = Charaly.space.gutter,
                    top = Charaly.space.sm,
                    bottom = Charaly.space.sm,
                ),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (step.previous != null) {
                    CharalyQuietAction(label = "Geri", onClick = { step.previous?.let { step = it } })
                }
                Spacer(Modifier.weight(1f))
                val next = step.next
                if (next != null) {
                    CharalyAction(
                        label = "İleri",
                        // The only gate in the wizard: nothing can be built without a name,
                        // and a forward button that is live but dead would teach that it can.
                        enabled = draft.hasTitle,
                        onClick = { step = next },
                    )
                }
            }
            if (!draft.hasTitle) {
                Text(
                    text = AuthoringCompiler.MISSING_TITLE,
                    style = MaterialTheme.typography.bodySmall,
                    color = Charaly.atmosphere.accent,
                    modifier = Modifier.padding(top = Charaly.space.xxs),
                )
            }
        }
    }
}

/**
 * The six steps, in order.
 *
 * The titles are the wizard's only navigation vocabulary, so they are Turkish and they are
 * nouns - each one says what is being decided, not which technical artifact is produced.
 */
enum class AuthoringStep(val number: Int, val title: String, val short: String) {
    KIMLIK(1, "KİMLİK", "Kimlik"),
    DUNYA(2, "DÜNYA", "Dünya"),
    KADRO(3, "KADRO", "Kadro"),
    HIKAYE(4, "HİKÂYE", "Hikâye"),
    KONTROL(5, "KONTROL", "Kontrol"),
    AKTAR(6, "DIŞA AKTAR", "Aktar"),
    ;

    val previous: AuthoringStep? get() = entries.getOrNull(ordinal - 1)
    val next: AuthoringStep? get() = entries.getOrNull(ordinal + 1)

    /** Whether this step reads the compiled pack: only the review and the export do. */
    val readsCompilation: Boolean get() = this == KONTROL || this == AKTAR
}

/**
 * Where the author is, as a row of pills.
 *
 * A labelled row rather than dots: this wizard has six steps with names, and a name does
 * the job of a number and a dot together. The pills are deliberately *not* tappable -
 * jumping to KONTROL with three steps unwritten would only land on a wall of findings - so
 * they carry no handler and are read as progress, which is what they are.
 */
@Composable
private fun AuthoringStepBar(step: AuthoringStep) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(Charaly.space.xs)) {
        items(AuthoringStep.entries, key = { it.name }) { entry ->
            CharalyPill(
                label = "${entry.number} ${entry.short}",
                selected = entry == step,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// 1. KIMLIK
// ---------------------------------------------------------------------------

private fun LazyListScope.identityStep(draft: AuthoringDraft, onDraftChange: (AuthoringDraft) -> Unit) {
    item(key = "kimlik-intro") {
        Text(
            text = "Bir paket; insanları, yerleri, kuralları ve dallarıyla bir dünya tarif eder. " +
                "Önce kim olduğunu yazın; motor geri kalanını yazdıklarınıza göre kurar.",
            style = MaterialTheme.typography.bodyLarge,
            color = Charaly.ink.secondary,
        )
    }
    item(key = "kimlik-title") {
        AuthoringField(
            label = "Ad *",
            value = draft.title,
            placeholder = "Son Fener",
            onChange = { onDraftChange(draft.copy(title = it)) },
        )
    }
    item(key = "kimlik-premise") {
        AuthoringField(
            label = "Önerme",
            value = draft.premise,
            placeholder = "Bu dünyanın ne olduğunu anlatan bir iki cümle.",
            onChange = { onDraftChange(draft.copy(premise = it)) },
            minHeight = 72.dp,
        )
    }
    item(key = "kimlik-description") {
        AuthoringField(
            label = "Tanım",
            value = draft.description,
            placeholder = "Kütüphanede ve mağazada görünecek uzun tanım.",
            onChange = { onDraftChange(draft.copy(description = it)) },
            minHeight = 96.dp,
        )
    }
    item(key = "kimlik-author") {
        AuthoringField(
            label = "Yazar",
            value = draft.author,
            placeholder = "Adınız ya da takma adınız.",
            onChange = { onDraftChange(draft.copy(author = it)) },
        )
    }
    item(key = "kimlik-publisher") {
        AuthoringField(
            label = "Yayıncı",
            value = draft.publisher,
            placeholder = "Dağıtıyorsanız yayıncının adı.",
            onChange = { onDraftChange(draft.copy(publisher = it)) },
        )
    }
    item(key = "kimlik-tags") {
        AuthoringField(
            label = "Etiketler",
            value = draft.tagsRaw,
            placeholder = "gizem, polisiye, gotik (virgülle ayırın)",
            onChange = { onDraftChange(draft.copy(tagsRaw = it)) },
        )
    }
    item(key = "kimlik-language") {
        OptionPicker(
            label = "İçerik dili",
            value = draft.language,
            options = listOf("tr" to "Türkçe", "en" to "English"),
            onSelect = { onDraftChange(draft.copy(language = it)) },
        )
    }
    item(key = "kimlik-cover") {
        AuthoringField(
            label = "Kapak tohumu",
            value = draft.coverSeed,
            placeholder = "Kapağın üretileceği tohum; boşsa ad kullanılır.",
            onChange = { onDraftChange(draft.copy(coverSeed = it)) },
        )
    }
    item(key = "kimlik-version") {
        AuthoringField(
            label = "Sürüm",
            value = draft.version,
            placeholder = AuthoringCompiler.DEFAULT_VERSION,
            onChange = { onDraftChange(draft.copy(version = it)) },
        )
    }
}

// ---------------------------------------------------------------------------
// 2. DUNYA
// ---------------------------------------------------------------------------

private fun LazyListScope.worldStep(draft: AuthoringDraft, onDraftChange: (AuthoringDraft) -> Unit) {
    item(key = "dunya-intro") {
        Text(
            text = "Dünya, oyuncu gelmeden önce doğru olan şeylerdir: evren, çağ, coğrafya, " +
                "kurallar ve mekânlar. Sahne ve dallar bir sonraki adımda bunların üstüne kurulur.",
            style = MaterialTheme.typography.bodyLarge,
            color = Charaly.ink.secondary,
        )
    }
    item(key = "dunya-universe") {
        AuthoringField(
            label = "Evren",
            value = draft.universe,
            placeholder = "Yağmur sonrası Paris",
            onChange = { onDraftChange(draft.copy(universe = it)) },
        )
    }
    item(key = "dunya-era") {
        AuthoringField(
            label = "Çağ",
            value = draft.era,
            placeholder = "Çağdaş",
            onChange = { onDraftChange(draft.copy(era = it)) },
        )
    }
    item(key = "dunya-geography") {
        AuthoringField(
            label = "Coğrafya",
            value = draft.geography,
            placeholder = "Eylemin geçtiği yer, sizin kelimelerinizle.",
            onChange = { onDraftChange(draft.copy(geography = it)) },
        )
    }

    item(key = "dunya-rules-head") {
        CharalySectionHeader(
            title = "Dünya kuralları",
            micro = true,
            caption = "Dünyanın uyguladığı katı kurallar. \"Sihir gerçektir ve bellek pahasına çalışır.\"",
        )
    }
    itemsIndexed(draft.rules, key = { _, row -> "rule-${row.rowId}" }) { _, row ->
        TextDraftRow(
            row = row,
            onChange = { changed -> onDraftChange(draft.copy(rules = draft.rules.updated(changed))) },
            onRemove = { onDraftChange(draft.copy(rules = draft.rules.removed(row.rowId))) },
        )
    }
    item(key = "dunya-rule-add") {
        CharalyQuietAction(
            label = "Kural ekle",
            icon = Icons.Filled.Add,
            onClick = { onDraftChange(draft.copy(rules = draft.rules + TextDraft())) },
        )
    }

    item(key = "dunya-facts-head") {
        CharalySectionHeader(
            title = "Dünya gerçekleri",
            micro = true,
            caption = "Başka hiçbir yerde yazmayan, burada doğru olan basit cümleler.",
        )
    }
    itemsIndexed(draft.worldFacts, key = { _, row -> "fact-${row.rowId}" }) { _, row ->
        TextDraftRow(
            row = row,
            onChange = { changed -> onDraftChange(draft.copy(worldFacts = draft.worldFacts.updated(changed))) },
            onRemove = { onDraftChange(draft.copy(worldFacts = draft.worldFacts.removed(row.rowId))) },
        )
    }
    item(key = "dunya-fact-add") {
        CharalyQuietAction(
            label = "Gerçek ekle",
            icon = Icons.Filled.Add,
            onClick = { onDraftChange(draft.copy(worldFacts = draft.worldFacts + TextDraft())) },
        )
    }

    item(key = "dunya-loc-head") {
        CharalySectionHeader(
            title = "Mekânlar",
            micro = true,
            caption = "Her hikâye bir yerde açılır. Karakterlerin başlangıç mekânı buradan seçilir.",
        )
    }
    itemsIndexed(draft.locations, key = { _, row -> "loc-${row.rowId}" }) { _, row ->
        LocationCard(
            row = row,
            onChange = { changed -> onDraftChange(draft.copy(locations = draft.locations.updated(changed))) },
            onRemove = { onDraftChange(draft.copy(locations = draft.locations.removed(row.rowId))) },
        )
    }
    item(key = "dunya-loc-add") {
        CharalyQuietAction(
            label = "Mekân ekle",
            icon = Icons.Filled.Add,
            onClick = { onDraftChange(draft.copy(locations = draft.locations + LocationDraft())) },
        )
    }

    item(key = "dunya-lore-head") {
        CharalySectionHeader(
            title = "Arka plan bilgisi",
            micro = true,
            caption = "İsteğe bağlı. Dünyanın tarihi, söylenceleri, perde arkası.",
        )
    }
    itemsIndexed(draft.lore, key = { _, row -> "lore-${row.rowId}" }) { _, row ->
        LoreCard(
            row = row,
            onChange = { changed -> onDraftChange(draft.copy(lore = draft.lore.updated(changed))) },
            onRemove = { onDraftChange(draft.copy(lore = draft.lore.removed(row.rowId))) },
        )
    }
    item(key = "dunya-lore-add") {
        CharalyQuietAction(
            label = "Bilgi ekle",
            icon = Icons.Filled.Add,
            onClick = { onDraftChange(draft.copy(lore = draft.lore + LoreDraft())) },
        )
    }
}

// ---------------------------------------------------------------------------
// 3. KADRO
// ---------------------------------------------------------------------------

private fun LazyListScope.castStep(draft: AuthoringDraft, onDraftChange: (AuthoringDraft) -> Unit) {
    val locationOptions = draft.locations.filter { it.name.isNotBlank() }.map { it.rowId to it.name.trim() }
    val characterOptions = draft.characters.filter { it.name.isNotBlank() }.map { it.rowId to it.name.trim() }

    item(key = "kadro-intro") {
        Text(
            text = "Kadro, hikâyeye kimlerin içinde yaşayacağını belirler. Roller, oyuncunun " +
                "üstlenebileceği rollerden ayrıdır: buradakiler dünyada yaşayan insanlardır.",
            style = MaterialTheme.typography.bodyLarge,
            color = Charaly.ink.secondary,
        )
    }

    item(key = "kadro-char-head") {
        CharalySectionHeader(
            title = "Karakterler",
            micro = true,
            caption = "Başlangıç mekânı, karakterin hikâye açıldığında nerede olduğunu belirler.",
        )
    }
    itemsIndexed(draft.characters, key = { _, row -> "char-${row.rowId}" }) { _, row ->
        CharacterCard(
            row = row,
            locationOptions = locationOptions,
            onChange = { changed -> onDraftChange(draft.copy(characters = draft.characters.updated(changed))) },
            onRemove = { onDraftChange(draft.copy(characters = draft.characters.removed(row.rowId))) },
        )
    }
    item(key = "kadro-char-add") {
        CharalyQuietAction(
            label = "Karakter ekle",
            icon = Icons.Filled.Add,
            onClick = { onDraftChange(draft.copy(characters = draft.characters + CharacterDraft())) },
        )
    }

    item(key = "kadro-persona-head") {
        CharalySectionHeader(
            title = "Roller",
            micro = true,
            caption = "Oyuncunun bu dünyada kim olabileceği. Yetenekler ve sınırlar dürüst yazılmalı.",
        )
    }
    itemsIndexed(draft.personas, key = { _, row -> "persona-${row.rowId}" }) { _, row ->
        PersonaCard(
            row = row,
            onChange = { changed -> onDraftChange(draft.copy(personas = draft.personas.updated(changed))) },
            onRemove = { onDraftChange(draft.copy(personas = draft.personas.removed(row.rowId))) },
        )
    }
    item(key = "kadro-persona-add") {
        CharalyQuietAction(
            label = "Rol ekle",
            icon = Icons.Filled.Add,
            onClick = { onDraftChange(draft.copy(personas = draft.personas + PersonaDraft())) },
        )
    }

    item(key = "kadro-rel-head") {
        CharalySectionHeader(
            title = "İlişkiler",
            micro = true,
            caption = "Kim kime nasıl bakıyor. Dört eksen bağımsızdır: sevilen ama güvenilmeyen biri olabilir.",
        )
    }
    itemsIndexed(draft.relationships, key = { _, row -> "rel-${row.rowId}" }) { _, row ->
        RelationshipCard(
            row = row,
            characterOptions = characterOptions,
            onChange = { changed -> onDraftChange(draft.copy(relationships = draft.relationships.updated(changed))) },
            onRemove = { onDraftChange(draft.copy(relationships = draft.relationships.removed(row.rowId))) },
        )
    }
    item(key = "kadro-rel-add") {
        CharalyQuietAction(
            label = "İlişki ekle",
            icon = Icons.Filled.Add,
            onClick = { onDraftChange(draft.copy(relationships = draft.relationships + RelationshipDraft())) },
        )
    }
}

// ---------------------------------------------------------------------------
// 4. HIKAYE
// ---------------------------------------------------------------------------

private fun LazyListScope.storyStep(draft: AuthoringDraft, onDraftChange: (AuthoringDraft) -> Unit) {
    val locationOptions = draft.locations.filter { it.name.isNotBlank() }.map { it.rowId to it.name.trim() }
    val characterOptions = draft.characters.filter { it.name.isNotBlank() }.map { it.rowId to it.name.trim() }
    val threadOptions = draft.threads.filter { it.title.isNotBlank() }.map { it.rowId to it.title.trim() }

    item(key = "hikaye-intro") {
        Text(
            text = "Hikâye kısmı çalışmayı kurar: açılışlar başlangıcı, sahneler durumları, " +
                "dallar seçimleri, görevler neyin kazanıldığını tarif eder. Bir dalın attığı " +
                "değişkeni bir görevin izlemesi, paketi gerçekten oynanır kılar.",
            style = MaterialTheme.typography.bodyLarge,
            color = Charaly.ink.secondary,
        )
    }

    item(key = "hikaye-scenario-head") {
        CharalySectionHeader(
            title = "Açılışlar",
            micro = true,
            caption = "Hikâyenin başlangıç yeri ve kadrosu. Kimlik, adından türetilir.",
        )
    }
    itemsIndexed(draft.scenarios, key = { _, row -> "scen-${row.rowId}" }) { _, row ->
        ScenarioCard(
            row = row,
            locationOptions = locationOptions,
            characterOptions = characterOptions,
            onChange = { changed -> onDraftChange(draft.copy(scenarios = draft.scenarios.updated(changed))) },
            onRemove = { onDraftChange(draft.copy(scenarios = draft.scenarios.removed(row.rowId))) },
        )
    }
    item(key = "hikaye-scenario-add") {
        CharalyQuietAction(
            label = "Açılış ekle",
            icon = Icons.Filled.Add,
            onClick = { onDraftChange(draft.copy(scenarios = draft.scenarios + ScenarioDraft())) },
        )
    }

    item(key = "hikaye-scene-head") {
        CharalySectionHeader(
            title = "Sahneler",
            micro = true,
            caption = "Hikâyenin varabileceği durumlar. Her sahnenin en az bir dalı olmalı.",
        )
    }
    itemsIndexed(draft.scenes, key = { _, row -> "scene-${row.rowId}" }) { _, row ->
        SceneCard(
            row = row,
            locationOptions = locationOptions,
            characterOptions = characterOptions,
            threadOptions = threadOptions,
            onChange = { changed -> onDraftChange(draft.copy(scenes = draft.scenes.updated(changed))) },
            onRemove = { onDraftChange(draft.copy(scenes = draft.scenes.removed(row.rowId))) },
        )
    }
    item(key = "hikaye-scene-add") {
        CharalyQuietAction(
            label = "Sahne ekle",
            icon = Icons.Filled.Add,
            // A scene with no branch is not a situation, it is a diorama: every scene starts
            // with exactly one branch so the author edits a real fork rather than a caption.
            onClick = {
                onDraftChange(
                    draft.copy(scenes = draft.scenes + SceneDraft(rowId = newRowId(), branches = listOf(BranchDraft()))),
                )
            },
        )
    }

    item(key = "hikaye-objective-head") {
        CharalySectionHeader(
            title = "Görevler",
            micro = true,
            caption = "Kazanmak \"değişken = değer\" olduğunda olur; kaybetmek isteğe bağlı ikinci koşulla.",
        )
    }
    itemsIndexed(draft.objectives, key = { _, row -> "obj-${row.rowId}" }) { _, row ->
        ObjectiveCard(
            row = row,
            onChange = { changed -> onDraftChange(draft.copy(objectives = draft.objectives.updated(changed))) },
            onRemove = { onDraftChange(draft.copy(objectives = draft.objectives.removed(row.rowId))) },
        )
    }
    item(key = "hikaye-objective-add") {
        CharalyQuietAction(
            label = "Görev ekle",
            icon = Icons.Filled.Add,
            onClick = { onDraftChange(draft.copy(objectives = draft.objectives + ObjectiveDraft())) },
        )
    }

    item(key = "hikaye-thread-head") {
        CharalySectionHeader(
            title = "Hikâye çizgileri",
            micro = true,
            caption = "Dünyanın takip ettiği yaylar. Dallarda \"çizgiyi ilerlet\" sonucu bunlara bağlanır.",
        )
    }
    itemsIndexed(draft.threads, key = { _, row -> "thread-${row.rowId}" }) { _, row ->
        ThreadCard(
            row = row,
            onChange = { changed -> onDraftChange(draft.copy(threads = draft.threads.updated(changed))) },
            onRemove = { onDraftChange(draft.copy(threads = draft.threads.removed(row.rowId))) },
        )
    }
    item(key = "hikaye-thread-add") {
        CharalyQuietAction(
            label = "Çizgi ekle",
            icon = Icons.Filled.Add,
            onClick = { onDraftChange(draft.copy(threads = draft.threads + ThreadDraft())) },
        )
    }

    item(key = "hikaye-event-head") {
        CharalySectionHeader(
            title = "Koşullu olay",
            micro = true,
            caption = "İsteğe bağlı. \"Bir değişken eşitlendiğinde\" tetiklenen tek bir olay.",
        )
    }
    item(key = "hikaye-event-toggle") {
        CharalyQuietAction(
            label = if (draft.event == null) "Koşullu olay ekle" else "Koşullu olayı kaldır",
            icon = Icons.Filled.Add,
            onClick = {
                onDraftChange(
                    draft.copy(event = if (draft.event == null) EventDraft(rowId = newRowId()) else null),
                )
            },
        )
    }
    val event = draft.event
    if (event != null) {
        item(key = "hikaye-event-card") {
            EventCard(
                row = event,
                characterOptions = characterOptions,
                threadOptions = threadOptions,
                onChange = { changed -> onDraftChange(draft.copy(event = changed)) },
                onRemove = { onDraftChange(draft.copy(event = null)) },
            )
        }
    }
}

// ---------------------------------------------------------------------------
// 5. KONTROL
// ---------------------------------------------------------------------------

/**
 * The review step: the real validators, in the import review's own presentation.
 *
 * The report is assembled by [AuthoringCompiler.review] - bundle, content, references,
 * reachability, branching, manifest - and projected through [PackImportReportUi], so this
 * step and the pack import screen cannot word the same finding differently.
 */
private fun LazyListScope.reviewStep(compiled: StoryPack?, report: PackImportReportUi?) {
    item(key = "kontrol-note") {
        Text(
            text = "Yazdıklarınız derlenip gerçek paket doğrulayıcılarından geçirildi. " +
                "Kritik hatalar kurulumu ve dışa aktarmayı engeller; uyarılara rağmen ilerleyebilirsiniz.",
            style = MaterialTheme.typography.bodyLarge,
            color = Charaly.ink.secondary,
        )
    }
    if (compiled == null || report == null) {
        item(key = "kontrol-missing") {
            Text(
                text = AuthoringCompiler.MISSING_TITLE,
                style = MaterialTheme.typography.bodyLarge,
                color = Charaly.atmosphere.accent,
            )
        }
        return
    }

    item(key = "kontrol-verdict") {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(CharalyShapes.soft)
                .background(Charaly.surface.raised)
                .padding(Charaly.space.lg),
        ) {
            Text(
                text = report.headline,
                style = MaterialTheme.typography.headlineMedium,
                color = Charaly.ink.primary,
            )
            if (report.censusLine.isNotBlank()) {
                Text(
                    text = report.censusLine,
                    style = MaterialTheme.typography.bodyLarge,
                    color = Charaly.ink.secondary,
                    modifier = Modifier.padding(top = Charaly.space.xs),
                )
            }
        }
    }

    if (report.errors.isNotEmpty()) {
        item(key = "kontrol-errors-head") {
            CharalySectionHeader(
                title = "Kritik Hatalar",
                micro = true,
                caption = "Bu hatalar dışa aktarmayı ve yüklemeyi engeller.",
            )
        }
        items(report.errors.size, key = { "kontrol-error-$it" }) { index ->
            IssueRowView(row = report.errors[index])
        }
    }
    if (report.warnings.isNotEmpty()) {
        item(key = "kontrol-warnings-head") {
            CharalySectionHeader(title = "Uyarılar", micro = true)
        }
        items(report.warnings.size, key = { "kontrol-warning-$it" }) { index ->
            IssueRowView(row = report.warnings[index])
        }
    }
}

// ---------------------------------------------------------------------------
// 6. DISA AKTAR
// ---------------------------------------------------------------------------

/**
 * The export step: the pack's manifest identity, and the two real doors out.
 *
 * "Kutuphaneye Yukle" runs the pack through `exportPack` then `import` - the exact bytes a
 * `.charaly-pack` file would carry, taken through the exact pipeline a sideloaded file
 * takes, so an authored pack is validated by nothing softer than an imported one. "Dosya
 * Olarak Kaydet" hands the same bytes to the shell's SAF `CreateDocument` launcher.
 *
 * Both are disabled on a CRITICAL finding rather than hidden: step 5 above is the reason,
 * and a vanished button explains nothing.
 */
private fun LazyListScope.exportStep(
    compiled: StoryPack?,
    report: PackImportReportUi?,
    existingIds: List<String>,
    onSave: (StoryPack) -> Unit,
    onExportFile: (StoryPack) -> Unit,
) {
    if (compiled == null || report == null) {
        item(key = "aktar-missing") {
            Text(
                text = AuthoringCompiler.MISSING_TITLE,
                style = MaterialTheme.typography.bodyLarge,
                color = Charaly.atmosphere.accent,
            )
        }
        return
    }

    item(key = "aktar-identity") {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(CharalyShapes.soft)
                .background(Charaly.surface.raised)
                .padding(Charaly.space.lg),
        ) {
            Text(
                text = "Paket kimliği",
                style = MaterialTheme.typography.labelMedium,
                color = Charaly.atmosphere.accent,
            )
            // The one place an id is shown to a person: it is the *author's own* id, the
            // name the archive and the library will carry. Read-only because it is derived
            // from the title - editing an id the compiler derives would desynchronise every
            // cross reference the pack was built with.
            Text(
                text = compiled.id.value,
                style = MaterialTheme.typography.bodyMedium,
                color = Charaly.ink.primary,
                modifier = Modifier.padding(top = Charaly.space.xxs),
            )
            Row(
                modifier = Modifier.padding(top = Charaly.space.sm),
                horizontalArrangement = Arrangement.spacedBy(Charaly.space.xs),
            ) {
                CharalyPill(label = packVersionLabel(compiled.semanticVersion))
                CharalyPill(label = compiled.language.uppercase())
            }
            if (compiled.id.value in existingIds) {
                Text(
                    text = "Bu kimlikte bir dünya bu cihazda zaten var. Yüklemek onu yeni bir " +
                        "sürüm olarak günceller; eski sürüm, o sürümle başlayan hikâyeler için korunur.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Charaly.ink.secondary,
                    modifier = Modifier.padding(top = Charaly.space.sm),
                )
            }
        }
    }

    item(key = "aktar-actions") {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(Charaly.space.xs),
        ) {
            Text(
                text = if (report.canInstall) {
                    "Kütüphaneye yüklemek paketi bu cihaza kurar ve Dünyalar'a ekler. Dosya olarak " +
                        "kaydetmek .charaly-pack arşivini seçtiğiniz yere yazar."
                } else {
                    "5. adımdaki kritik hatalar dururken paket ne kurulabilir ne de kaydedilebilir. " +
                        "Hataları düzeltin, sonra buraya dönün."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = if (report.canInstall) Charaly.ink.secondary else Charaly.atmosphere.accent,
            )
            CharalyAction(
                label = "Kütüphaneye Yükle",
                onClick = { onSave(compiled) },
                enabled = report.canInstall,
                fillWidth = true,
            )
            CharalyQuietAction(
                label = "Dosya Olarak Kaydet",
                onClick = { onExportFile(compiled) },
                modifier = Modifier.fillMaxWidth(),
                enabled = report.canInstall,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Editor building blocks
// ---------------------------------------------------------------------------

/**
 * One repeated row of plain text (a world rule, a world fact): field plus a remove control.
 *
 * The remove control is an icon button rather than a gesture, for the same reason the
 * library has no swipe-to-delete: dropping a world rule must be deliberate.
 */
@Composable
private fun TextDraftRow(row: TextDraft, onChange: (TextDraft) -> Unit, onRemove: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        AuthoringField(
            label = "",
            value = row.text,
            placeholder = "Tek cümle.",
            onChange = { onChange(row.copy(text = it)) },
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(Charaly.space.xs))
        CharalyIconButton(
            icon = Icons.Filled.Delete,
            contentDescription = "Bu satırı kaldır",
            onClick = { onRemove() },
        )
    }
}

/** One place in the world. Interior/exterior is the one structural property asked for. */
@Composable
private fun LocationCard(row: LocationDraft, onChange: (LocationDraft) -> Unit, onRemove: () -> Unit) {
    AuthoringCard(title = row.name.ifBlank { "Yeni mekân" }, onRemove = onRemove) {
        AuthoringField(
            label = "Ad",
            value = row.name,
            placeholder = "İskele",
            onChange = { onChange(row.copy(name = it)) },
        )
        AuthoringField(
            label = "Tanım",
            value = row.description,
            placeholder = "Buranın nasıl bir yer olduğu.",
            onChange = { onChange(row.copy(description = it)) },
            minHeight = 72.dp,
        )
        AuthoringToggle(
            label = "İç mekân",
            caption = "Kapalı bir mekân mı, açık bir alan mı?",
            checked = row.interior,
            onChange = { onChange(row.copy(interior = it)) },
        )
    }
}

/** One piece of authored background. */
@Composable
private fun LoreCard(row: LoreDraft, onChange: (LoreDraft) -> Unit, onRemove: () -> Unit) {
    AuthoringCard(title = row.title.ifBlank { "Yeni bilgi" }, onRemove = onRemove) {
        AuthoringField(
            label = "Başlık",
            value = row.title,
            placeholder = "Fenerin geçmişi",
            onChange = { onChange(row.copy(title = it)) },
        )
        AuthoringField(
            label = "İçerik",
            value = row.content,
            placeholder = "Bu bilginin kendisi.",
            onChange = { onChange(row.copy(content = it)) },
            minHeight = 96.dp,
        )
    }
}

/** One person in the cast. */
@Composable
private fun CharacterCard(
    row: CharacterDraft,
    locationOptions: List<Pair<String, String>>,
    onChange: (CharacterDraft) -> Unit,
    onRemove: () -> Unit,
) {
    AuthoringCard(title = row.name.ifBlank { "Yeni karakter" }, onRemove = onRemove) {
        AuthoringField(
            label = "Ad",
            value = row.name,
            placeholder = "Deniz",
            onChange = { onChange(row.copy(name = it)) },
        )
        AuthoringField(
            label = "Slogan",
            value = row.tagline,
            placeholder = "Adın altında tek satır.",
            onChange = { onChange(row.copy(tagline = it)) },
        )
        AuthoringField(
            label = "Tanım",
            value = row.description,
            placeholder = "Nasıl göründüğü, ne yaptığı.",
            onChange = { onChange(row.copy(description = it)) },
            minHeight = 72.dp,
        )
        AuthoringField(
            label = "Kişilik",
            value = row.personality,
            placeholder = "Kuru, gözlemci, lakayt.",
            onChange = { onChange(row.copy(personality = it)) },
        )
        OptionPicker(
            label = "Başlangıç mekânı",
            value = row.startLocationRowId,
            options = locationOptions,
            onSelect = { onChange(row.copy(startLocationRowId = it)) },
        )
        OptionPicker(
            label = "Rol",
            value = row.role.name,
            options = CharacterRole.entries.map { it.name to it.turkishLabel },
            onSelect = { name -> onChange(row.copy(role = CharacterRole.valueOf(name))) },
        )
    }
}

/** One role the player may take. */
@Composable
private fun PersonaCard(row: PersonaDraft, onChange: (PersonaDraft) -> Unit, onRemove: () -> Unit) {
    AuthoringCard(title = row.name.ifBlank { "Yeni rol" }, onRemove = onRemove) {
        AuthoringField(
            label = "Ad",
            value = row.name,
            placeholder = "Çaylak gazeteci",
            onChange = { onChange(row.copy(name = it)) },
        )
        AuthoringField(
            label = "Slogan",
            value = row.tagline,
            placeholder = "Bu rol tek cümlede.",
            onChange = { onChange(row.copy(tagline = it)) },
        )
        AuthoringField(
            label = "Rol yönergesi",
            value = row.rolePrompt,
            placeholder = "Modelin bu rolde kim olacağına dair yönerge.",
            onChange = { onChange(row.copy(rolePrompt = it)) },
            minHeight = 72.dp,
        )
        AuthoringField(
            label = "Yetenekler",
            value = row.abilitiesRaw,
            placeholder = "Sıradan birinin yapamayacakları (virgülle ayırın)",
            onChange = { onChange(row.copy(abilitiesRaw = it)) },
        )
        AuthoringField(
            label = "Kısıtlar",
            value = row.restrictionsRaw,
            placeholder = "Bu rolün yapamayacakları (virgülle ayırın)",
            onChange = { onChange(row.copy(restrictionsRaw = it)) },
        )
    }
}

/** One directed relationship, with the four axes that make it more than a score. */
@Composable
private fun RelationshipCard(
    row: RelationshipDraft,
    characterOptions: List<Pair<String, String>>,
    onChange: (RelationshipDraft) -> Unit,
    onRemove: () -> Unit,
) {
    val sourceName = characterOptions.firstOrNull { it.first == row.sourceRowId }?.second.orEmpty()
    val targetName = characterOptions.firstOrNull { it.first == row.targetRowId }?.second.orEmpty()
    AuthoringCard(
        title = if (sourceName.isNotBlank() && targetName.isNotBlank()) {
            "$sourceName → $targetName"
        } else {
            "Yeni ilişki"
        },
        onRemove = onRemove,
    ) {
        OptionPicker(
            label = "Kimden",
            value = row.sourceRowId,
            options = characterOptions,
            onSelect = { onChange(row.copy(sourceRowId = it)) },
        )
        OptionPicker(
            label = "Kime",
            value = row.targetRowId,
            options = characterOptions,
            onSelect = { onChange(row.copy(targetRowId = it)) },
        )
        OptionPicker(
            label = "Tür",
            value = row.typeName,
            options = listOf("" to "Belirtilmedi") + relationshipTypeOptions(),
            onSelect = { onChange(row.copy(typeName = it)) },
        )
        SliderField(
            label = "Güven",
            value = row.trust,
            onChange = { onChange(row.copy(trust = it)) },
        )
        SliderField(
            label = "Yakınlık",
            value = row.affinity,
            onChange = { onChange(row.copy(affinity = it)) },
        )
        SliderField(
            label = "Gerginlik",
            value = row.tension,
            onChange = { onChange(row.copy(tension = it)) },
        )
        SliderField(
            label = "Tanışıklık",
            value = row.familiarity,
            onChange = { onChange(row.copy(familiarity = it)) },
        )
    }
}

/** One opening: where the story starts and who is in the room. */
@Composable
private fun ScenarioCard(
    row: ScenarioDraft,
    locationOptions: List<Pair<String, String>>,
    characterOptions: List<Pair<String, String>>,
    onChange: (ScenarioDraft) -> Unit,
    onRemove: () -> Unit,
) {
    AuthoringCard(title = row.title.ifBlank { "Yeni açılış" }, onRemove = onRemove) {
        AuthoringField(
            label = "Ad",
            value = row.title,
            placeholder = "İlk Gün",
            onChange = { onChange(row.copy(title = it)) },
        )
        AuthoringField(
            label = "Slogan",
            value = row.tagline,
            placeholder = "Bu açılışı tek cümlede anlatan satır.",
            onChange = { onChange(row.copy(tagline = it)) },
        )
        OptionPicker(
            label = "Başlangıç mekânı",
            value = row.startLocationRowId,
            options = locationOptions,
            onSelect = { onChange(row.copy(startLocationRowId = it)) },
        )
        OptionPicker(
            label = "Odak karakter",
            value = row.focusCharacterRowId,
            options = listOf("" to "Kimse") + characterOptions,
            onSelect = { onChange(row.copy(focusCharacterRowId = it)) },
        )
        Text(
            text = "Açılış kadrosu",
            style = MaterialTheme.typography.labelMedium,
            color = Charaly.ink.muted,
            modifier = Modifier.padding(top = Charaly.space.xs),
        )
        MultiToggle(
            options = characterOptions,
            selected = row.castRowIds,
            onToggle = { rowId, selected ->
                onChange(
                    row.copy(
                        castRowIds = if (selected) row.castRowIds + rowId else row.castRowIds - rowId,
                    ),
                )
            },
        )
    }
}

/**
 * One scene: a place, a cast, and at least one real fork.
 *
 * The branches are plain rows inside the card rather than a nested list, because a
 * lazily-scrolled list inside a lazily-measured entry is the crash the nested-scroll guard
 * exists to prevent - and because a scene's forks should read as part of the scene.
 */
@Composable
private fun SceneCard(
    row: SceneDraft,
    locationOptions: List<Pair<String, String>>,
    characterOptions: List<Pair<String, String>>,
    threadOptions: List<Pair<String, String>>,
    onChange: (SceneDraft) -> Unit,
    onRemove: () -> Unit,
) {
    AuthoringCard(title = row.name.ifBlank { "Yeni sahne" }, onRemove = onRemove) {
        AuthoringField(
            label = "Ad",
            value = row.name,
            placeholder = "İskelede buluşma",
            onChange = { onChange(row.copy(name = it)) },
        )
        AuthoringField(
            label = "Tanım",
            value = row.description,
            placeholder = "Bu durumda ne oluyor.",
            onChange = { onChange(row.copy(description = it)) },
            minHeight = 72.dp,
        )
        AuthoringField(
            label = "Atmosfer",
            value = row.atmosphere,
            placeholder = "\"gergin\", \"sıcak\", \"absürt\"",
            onChange = { onChange(row.copy(atmosphere = it)) },
        )
        OptionPicker(
            label = "Mekân",
            value = row.locationRowId,
            options = locationOptions,
            onSelect = { onChange(row.copy(locationRowId = it)) },
        )
        Text(
            text = "Katılımcılar",
            style = MaterialTheme.typography.labelMedium,
            color = Charaly.ink.muted,
            modifier = Modifier.padding(top = Charaly.space.xs),
        )
        MultiToggle(
            options = characterOptions,
            selected = row.participants,
            onToggle = { rowId, selected ->
                onChange(
                    row.copy(
                        participants = if (selected) row.participants + rowId else row.participants - rowId,
                    ),
                )
            },
        )

        Text(
            text = "Dallar",
            style = MaterialTheme.typography.labelMedium,
            color = Charaly.ink.muted,
            modifier = Modifier.padding(top = Charaly.space.sm),
        )
        row.branches.forEach { branch ->
            BranchCard(
                branch = branch,
                characterOptions = characterOptions,
                threadOptions = threadOptions,
                onChange = { changed ->
                    onChange(row.copy(branches = row.branches.updated(changed)))
                },
                onRemove = { onChange(row.copy(branches = row.branches.removed(branch.rowId))) },
            )
        }
        CharalyQuietAction(
            label = "Dal ekle",
            icon = Icons.Filled.Add,
            onClick = { onChange(row.copy(branches = row.branches + BranchDraft())) },
        )
    }
}

/** One fork inside a scene: a label in the player's voice and one typed consequence. */
@Composable
private fun BranchCard(
    branch: BranchDraft,
    characterOptions: List<Pair<String, String>>,
    threadOptions: List<Pair<String, String>>,
    onChange: (BranchDraft) -> Unit,
    onRemove: () -> Unit,
) {
    AuthoringCard(title = branch.label.ifBlank { "Yeni dal" }, onRemove = onRemove) {
        AuthoringField(
            label = "Seçenek metni",
            value = branch.label,
            placeholder = "Feneri kıyıya götür",
            onChange = { onChange(branch.copy(label = it)) },
        )
        EffectEditor(
            effect = branch.effect,
            characterOptions = characterOptions,
            threadOptions = threadOptions,
            onChange = { onChange(branch.copy(effect = it)) },
        )
    }
}

/**
 * The small, real effect editor: one of three typed consequences.
 *
 * Three kinds and not thirty, because this is the wizard's fork editor and not the event
 * editor: a flag, a nudge of trust, the plot moving. Each kind is a closed, typed effect -
 * the same `EventEffect` set the engine accepts - so what the author picks here cannot
 * degrade into prose that changes nothing.
 */
@Composable
private fun EffectEditor(
    effect: EffectDraft,
    characterOptions: List<Pair<String, String>>,
    threadOptions: List<Pair<String, String>>,
    onChange: (EffectDraft) -> Unit,
) {
    OptionPicker(
        label = "Sonuç",
        value = effect.kind.name,
        options = EffectKind.entries.map { it.name to it.label },
        onSelect = { onChange(effect.copy(kind = EffectKind.valueOf(it))) },
    )
    when (effect.kind) {
        EffectKind.SET_VARIABLE -> {
            AuthoringField(
                label = "Değişken anahtarı",
                value = effect.variableKey,
                placeholder = "fener_teslim_edildi",
                onChange = { onChange(effect.copy(variableKey = it)) },
            )
            AuthoringField(
                label = "Değer",
                value = effect.variableValue,
                placeholder = "evet",
                onChange = { onChange(effect.copy(variableValue = it)) },
            )
        }

        EffectKind.TRUST_SHIFT -> {
            OptionPicker(
                label = "Kimden",
                value = effect.sourceRowId,
                options = characterOptions,
                onSelect = { onChange(effect.copy(sourceRowId = it)) },
            )
            OptionPicker(
                label = "Kime",
                value = effect.targetRowId,
                options = characterOptions,
                onSelect = { onChange(effect.copy(targetRowId = it)) },
            )
            SliderField(
                label = "Güven değişimi",
                value = effect.trustDelta,
                range = -50..50,
                onChange = { onChange(effect.copy(trustDelta = it)) },
            )
        }

        EffectKind.ADVANCE_THREAD -> {
            OptionPicker(
                label = "Hikâye çizgisi",
                value = effect.threadRowId,
                options = threadOptions,
                onSelect = { onChange(effect.copy(threadRowId = it)) },
            )
        }
    }
}

/**
 * One objective: what the story asks, and the exact variable state that wins or loses it.
 *
 * Both conditions are `variable key equals value` - deliberately the same shape scene
 * branches write, which is what makes an authored objective *reachable*: the fork sets the
 * variable, the objective watches it, and the story has an end the engine can see.
 */
@Composable
private fun ObjectiveCard(row: ObjectiveDraft, onChange: (ObjectiveDraft) -> Unit, onRemove: () -> Unit) {
    AuthoringCard(title = row.title.ifBlank { "Yeni görev" }, onRemove = onRemove) {
        AuthoringField(
            label = "Ad",
            value = row.title,
            placeholder = "Feneri sahibine ulaştır",
            onChange = { onChange(row.copy(title = it)) },
        )
        AuthoringField(
            label = "Tanım",
            value = row.description,
            placeholder = "Görevin ne istediği.",
            onChange = { onChange(row.copy(description = it)) },
            minHeight = 72.dp,
        )
        Text(
            text = "Kazanma koşulu · değişken = değer",
            style = MaterialTheme.typography.labelMedium,
            color = Charaly.ink.muted,
            modifier = Modifier.padding(top = Charaly.space.xs),
        )
        AuthoringField(
            label = "Değişken anahtarı",
            value = row.completionKey,
            placeholder = "fener_teslim_edildi",
            onChange = { onChange(row.copy(completionKey = it)) },
        )
        AuthoringField(
            label = "Eşitlenmesi gereken değer",
            value = row.completionValue,
            placeholder = "evet",
            onChange = { onChange(row.copy(completionValue = it)) },
        )
        Text(
            text = "Kaybetme koşulu · isteğe bağlı",
            style = MaterialTheme.typography.labelMedium,
            color = Charaly.ink.muted,
            modifier = Modifier.padding(top = Charaly.space.xs),
        )
        AuthoringField(
            label = "Değişken anahtarı",
            value = row.failureKey,
            placeholder = "fener_kayboldu",
            onChange = { onChange(row.copy(failureKey = it)) },
        )
        AuthoringField(
            label = "Eşitlenmesi gereken değer",
            value = row.failureValue,
            placeholder = "evet",
            onChange = { onChange(row.copy(failureValue = it)) },
        )
    }
}

/** One narrative thread: an arc the world keeps track of. */
@Composable
private fun ThreadCard(row: ThreadDraft, onChange: (ThreadDraft) -> Unit, onRemove: () -> Unit) {
    AuthoringCard(title = row.title.ifBlank { "Yeni çizgi" }, onRemove = onRemove) {
        AuthoringField(
            label = "Ad",
            value = row.title,
            placeholder = "Kayıp fener",
            onChange = { onChange(row.copy(title = it)) },
        )
        AuthoringField(
            label = "Tanım",
            value = row.description,
            placeholder = "Bu yay ne hakkında.",
            onChange = { onChange(row.copy(description = it)) },
            minHeight = 72.dp,
        )
    }
}

/**
 * The one optional conditional event: "when key equals value, do this".
 *
 * The trigger is written as two plain fields rather than a condition grammar on purpose -
 * one variable, one value, one effect is an event an author can hold in their head, and
 * the engine evaluates it deterministically whether or not the model notices.
 */
@Composable
private fun EventCard(
    row: EventDraft,
    characterOptions: List<Pair<String, String>>,
    threadOptions: List<Pair<String, String>>,
    onChange: (EventDraft) -> Unit,
    onRemove: () -> Unit,
) {
    AuthoringCard(title = row.title.ifBlank { "Yeni koşullu olay" }, onRemove = onRemove) {
        AuthoringField(
            label = "Ad",
            value = row.title,
            placeholder = "Fener bulundu",
            onChange = { onChange(row.copy(title = it)) },
        )
        Text(
            text = "Ne zaman · değişken = değer",
            style = MaterialTheme.typography.labelMedium,
            color = Charaly.ink.muted,
            modifier = Modifier.padding(top = Charaly.space.xs),
        )
        AuthoringField(
            label = "Değişken anahtarı",
            value = row.whenKey,
            placeholder = "fener_bulundu",
            onChange = { onChange(row.copy(whenKey = it)) },
        )
        AuthoringField(
            label = "Eşitlenmesi gereken değer",
            value = row.whenValue,
            placeholder = "evet",
            onChange = { onChange(row.copy(whenValue = it)) },
        )
        EffectEditor(
            effect = row.effect,
            characterOptions = characterOptions,
            threadOptions = threadOptions,
            onChange = { onChange(row.copy(effect = it)) },
        )
    }
}

// ---------------------------------------------------------------------------
// Form controls
// ---------------------------------------------------------------------------

/**
 * The card chrome every editor row shares: a title, a remove control, and plain content.
 *
 * A `Column` and not a lazily-measured surface, so it can live inside a lazy entry without
 * the nested-scroll crash - and so a scene's branches can nest inside a scene card as
 * ordinary layout rather than as a list within a list.
 */
@Composable
private fun AuthoringCard(
    title: String,
    onRemove: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(CharalyShapes.soft)
            .background(Charaly.surface.raised)
            .padding(Charaly.space.md),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = Charaly.ink.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (onRemove != null) {
                CharalyIconButton(
                    icon = Icons.Filled.Delete,
                    contentDescription = "\"$title\" kartını kaldır",
                    onClick = { onRemove() },
                )
            }
        }
        content()
    }
}

/**
 * One field.
 *
 * A `BasicTextField` on a filled surface, with the label *above* it rather than floating
 * inside. This is the one screen in the app that is a form, and it uses a form control -
 * but it uses the same one as everything else, so the app does not suddenly acquire two
 * input languages.
 */
@Composable
private fun AuthoringField(
    label: String,
    value: String,
    placeholder: String,
    onChange: (String) -> Unit,
    minHeight: Dp = 52.dp,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        if (label.isNotBlank()) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = Charaly.ink.muted,
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = if (label.isNotBlank()) Charaly.space.xxs else 0.dp)
                .clip(CharalyShapes.soft)
                .background(Charaly.surface.base)
                .padding(horizontal = Charaly.space.md, vertical = Charaly.space.sm),
        ) {
            if (value.isEmpty()) {
                Text(
                    text = placeholder,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Charaly.ink.muted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            BasicTextField(
                value = value,
                onValueChange = onChange,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = Charaly.ink.primary),
                cursorBrush = SolidColor(Charaly.atmosphere.accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(minHeight)
                    .semantics { contentDescription = label.ifBlank { placeholder } },
            )
        }
    }
}

/** A labelled toggle. The whole row reads as one setting, switch and words together. */
@Composable
private fun AuthoringToggle(
    label: String,
    caption: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Charaly.space.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                color = Charaly.ink.primary,
            )
            if (caption.isNotBlank()) {
                Text(
                    text = caption,
                    style = MaterialTheme.typography.bodySmall,
                    color = Charaly.ink.muted,
                )
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            modifier = Modifier.semantics { contentDescription = label },
        )
    }
}

/**
 * A dropdown over the rows that exist.
 *
 * Offered as a pill that opens a menu rather than as a spinner or a dialog: the picker is
 * always about *which of the things I just wrote*, so the current answer should sit on the
 * card face. An empty option list shows "Önce ekleyin" and refuses to open, because a menu
 * of nothing is a dead control.
 */
@Composable
private fun OptionPicker(
    label: String,
    value: String,
    options: List<Pair<String, String>>,
    onSelect: (String) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val selectedLabel = options.firstOrNull { it.first == value }?.second
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = Charaly.space.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = Charaly.ink.muted,
            modifier = Modifier.weight(1f),
        )
        Box {
            CharalyPill(
                label = when {
                    options.isEmpty() -> "Önce ekleyin"
                    selectedLabel != null -> selectedLabel
                    else -> "Seçilmedi"
                },
                onClick = { open = true },
                enabled = options.isNotEmpty(),
                icon = Icons.Filled.KeyboardArrowDown,
                contentDescription = "$label seçimi",
            )
            DropdownMenu(
                expanded = open,
                onDismissRequest = { open = false },
            ) {
                options.forEach { (optionId, optionLabel) ->
                    DropdownMenuItem(
                        text = { Text(optionLabel) },
                        onClick = {
                            onSelect(optionId)
                            open = false
                        },
                    )
                }
            }
        }
    }
}

/**
 * A multi-select over the rows that exist: one toggle row per option.
 *
 * Toggle *rows* rather than a wrapping cloud of chips, because a cast is a considered list
 * - and because a wrapping layout measured inside a lazy entry is exactly the shape the
 * nested-scroll guard exists to prevent. Selection changes the pill's fill and border as
 * well as its word, so it survives without colour.
 */
@Composable
private fun MultiToggle(
    options: List<Pair<String, String>>,
    selected: Set<String>,
    onToggle: (String, Boolean) -> Unit,
) {
    if (options.isEmpty()) {
        Text(
            text = "Önce ekleyin.",
            style = MaterialTheme.typography.bodySmall,
            color = Charaly.ink.muted,
            modifier = Modifier.padding(top = Charaly.space.xxs),
        )
        return
    }
    options.forEach { (rowId, name) ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = Charaly.space.xxs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = name,
                style = MaterialTheme.typography.bodyMedium,
                color = Charaly.ink.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            val isSelected = rowId in selected
            CharalyPill(
                label = if (isSelected) "Seçildi" else "Ekle",
                selected = isSelected,
                onClick = { onToggle(rowId, !isSelected) },
                contentDescription = if (isSelected) "$name seçimini kaldır" else "$name seç",
            )
        }
    }
}

/** One integer axis, as a slider with its number spelled out beside the label. */
@Composable
private fun SliderField(
    label: String,
    value: Int,
    range: IntRange = 0..100,
    onChange: (Int) -> Unit,
) {
    Column(Modifier.padding(top = Charaly.space.xs)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = Charaly.ink.muted,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = if (value > 0 && range.first < 0) "+$value" else "$value",
                style = MaterialTheme.typography.bodyMedium,
                color = Charaly.ink.primary,
            )
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { onChange(it.toInt()) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = label },
        )
    }
}

/**
 * One finding: its sentence and its severity, and nothing else.
 *
 * Identical to the import review's row, deliberately: [IssueRow] carries exactly a Turkish
 * sentence and a severity word, so no id or digest from a validator detail can reach this
 * step through some future field. The severity rides as a word beside the message, so the
 * difference between a blocker and a warning survives colour-blindness.
 */
@Composable
private fun IssueRowView(row: IssueRow) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(CharalyShapes.soft)
            .background(Charaly.surface.base)
            .padding(Charaly.space.md),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = row.message,
            style = MaterialTheme.typography.bodyMedium,
            color = Charaly.ink.primary,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(Charaly.space.sm))
        CharalyPill(label = row.severityLabel)
    }
}

// ---------------------------------------------------------------------------
// Small pure helpers
// ---------------------------------------------------------------------------

/** Replaces the row with the same [DraftRow.rowId], so an edit never disturbs its neighbours. */
private fun <R : dev.charaly.app.ui.packstore.DraftRow> List<R>.updated(row: R): List<R> =
    map { if (it.rowId == row.rowId) row else it }

/** Drops the row with this [DraftRow.rowId]. */
private fun <R : dev.charaly.app.ui.packstore.DraftRow> List<R>.removed(rowId: String): List<R> =
    filterNot { it.rowId == rowId }

/**
 * The role dropdown's Turkish names.
 *
 * The enum is `CharacterRole`'s own three entries - PROTAGONIST, NPC, BACKGROUND - renamed
 * for the dropdown only. Renaming here rather than in the enum is deliberate: the runtime's
 * vocabulary stays machine-sharp and the wizard's stays human.
 */
private val CharacterRole.turkishLabel: String
    get() = when (this) {
        CharacterRole.PROTAGONIST -> "Başrol"
        CharacterRole.NPC -> "Yardımcı karakter"
        CharacterRole.BACKGROUND -> "Arka plan"
    }

/** The optional relationship types, in Turkish, for the "Tür" dropdown. */
private fun relationshipTypeOptions(): List<Pair<String, String>> = listOf(
    "STRANGER" to "Yabancı",
    "ACQUAINTANCE" to "Tanıdık",
    "FRIEND" to "Arkadaş",
    "CLOSE_FRIEND" to "Yakın arkadaş",
    "ALLY" to "Müttefik",
    "RIVAL" to "Rakip",
    "ENEMY" to "Düşman",
    "MENTOR" to "Akıl hocası",
    "STUDENT" to "Öğrenci",
    "FAMILY" to "Aile",
    "PARTNER" to "Ortak",
    "UNKNOWN" to "Sınıflandırılmamış",
)