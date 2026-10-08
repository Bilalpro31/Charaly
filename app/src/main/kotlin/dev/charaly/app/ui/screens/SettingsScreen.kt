package dev.charaly.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.charaly.app.ui.components.CharalyAction
import dev.charaly.app.ui.components.CharalyIconButton
import dev.charaly.app.ui.components.CharalyPill
import dev.charaly.app.ui.components.CharalyQuietAction
import dev.charaly.app.ui.components.CharalySectionHeader
import dev.charaly.app.ui.components.CharalySurface
import dev.charaly.app.ui.design.Charaly
import dev.charaly.app.ui.design.CharalyShapes
import dev.charaly.runtime.presentation.Loc
import dev.charaly.runtime.presentation.SettingsPresenter
import dev.charaly.runtime.presentation.SettingsSection

/**
 * SETTINGS.
 *
 * ## Calm, and honest
 *
 * Two properties matter more than the layout here.
 *
 * **It does not leak engine internals.** No class names, no context budgets, no event
 * types, no JSON. The engine's own view lives behind Developer Mode, which is off unless
 * someone turns it on.
 *
**Its privacy copy matches the manifest.** This build *declares*
 * `android.permission.INTERNET`, for exactly one feature: fetching a GGUF from the Hub when
 * the user asks for one. So the sentence names the permission and what it is for. An
 * earlier version claimed the app had no network access at all, which was true of the
 * offline core build and became a false statement about this one the moment a real
 * transfer pipeline was wired - on the one screen whose entire purpose is to be believed.
 */
@Composable
fun SettingsScreen(
    darkTheme: Boolean,
    reduceMotion: Boolean,
    developerMode: Boolean,
    modelReady: Boolean,
    /** One word for the model state, from the authoritative selection. */
    modelState: String = "",
    /** The real reason when a model cannot be used; empty when nothing is wrong. */
    modelDetail: String = "",
    /** The one authoritative selection, for the local-AI sentence. */
    model: dev.charaly.runtime.model.ModelSelection =
        dev.charaly.runtime.model.ModelSelection(),
    activeModelName: String,
    installedModelCount: Int,
    storyCount: Int,
    installedBytes: Long,
    versionName: String,
    /** "en" or "tr". Empty means the screen has not been told yet. */
    languageTag: String = "",
    onBack: () -> Unit,
    onSetDarkTheme: (Boolean) -> Unit,
    onSetReduceMotion: (Boolean) -> Unit,
    onSetDeveloperMode: (Boolean) -> Unit,
    onSetLanguage: (String) -> Unit = {},
    onOpenModels: () -> Unit,
    onOpenAuthoring: () -> Unit,
    onOpenDeveloper: () -> Unit,
    /** The result of a real load-and-generate, or the idle state. */
    modelTest: dev.charaly.app.model.ModelTestPhase = dev.charaly.app.model.ModelTestPhase.IDLE,
    /**
     * The model the test would run against.
     *
     * Null means nothing is installed, and the screen says so instead of offering a button
     * that can only fail.
     */
    modelTestSubjectName: String = "",
    onRunModelTest: () -> Unit = {},
    onClearModelTest: () -> Unit = {},
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(Charaly.surface.void),
        contentPadding = PaddingValues(
            start = Charaly.space.gutter,
            end = Charaly.space.gutter,
            top = Charaly.space.xxl,
            bottom = Charaly.space.section,
        ),
        verticalArrangement = Arrangement.spacedBy(Charaly.space.lg),
    ) {
        item(key = "back") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CharalyIconButton(
                    icon = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = Loc.t("action.back"),
                    onClick = onBack,
                )
                Spacer(Modifier.size(Charaly.space.xs))
                Text(
                    text = Loc.t("settings.title"),
                    style = MaterialTheme.typography.headlineLarge,
                    color = Charaly.ink.primary,
                    modifier = Modifier.semantics { heading() },
                )
            }
        }

        // ---- APP --------------------------------------------------------
        item(key = "app") {
            SettingsGroup(SettingsSection.APP) {
                SettingSwitch(
                    title = Loc.t("settings.dark"),
                    caption = Loc.t("settings.dark_caption"),
                    checked = darkTheme,
                    contentDescription = Loc.t("settings.dark"),
                    onCheckedChange = onSetDarkTheme,
                )
                SettingSwitch(
                    title = Loc.t("settings.reduce_motion"),
                    caption = Loc.t("settings.reduce_motion_caption"),
                    checked = reduceMotion,
                    contentDescription = Loc.t("settings.reduce_motion"),
                    onCheckedChange = onSetReduceMotion,
                )
                Text(
                    text = Loc.t("settings.language"),
                    style = MaterialTheme.typography.titleSmall,
                    color = Charaly.ink.primary,
                    modifier = Modifier.padding(top = Charaly.space.md),
                )
                Spacer(Modifier.height(Charaly.space.xs))
                // A two-way choice rather than a dropdown: there are exactly two languages,
                // and both names are shown in their own language, which is the only
                // unambiguous way to write them.
                Row(horizontalArrangement = Arrangement.spacedBy(Charaly.space.xs)) {
                    CharalyPill(
                        label = Loc.t("settings.language_english"),
                        selected = languageTag == "en",
                        onClick = { onSetLanguage("en") },
                        contentDescription = Loc.t("settings.language_english"),
                    )
                    CharalyPill(
                        label = Loc.t("settings.language_turkish"),
                        selected = languageTag == "tr",
                        onClick = { onSetLanguage("tr") },
                        contentDescription = Loc.t("settings.language_turkish"),
                    )
                }
            }
        }

        // ---- AI ----------------------------------------------------------
        item(key = "ai") {
            SettingsGroup(SettingsSection.AI) {
                Text(
                    // Read from the authoritative selection. The old signature took a
                    // boolean derived from engine residency, which is why this screen told a
                    // user with an imported GGUF that no model was loaded.
                    text = SettingsPresenter.localAiBody(model, activeModelName),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Charaly.ink.secondary,
                )
                // The real state, from the one authoritative selection. Previously this
                // screen said "a local model is needed" for a model that was imported and
                // merely still loading, because it read engine residency.
                if (modelState.isNotBlank()) {
                    Text(
                        text = modelState + if (modelDetail.isNotBlank()) " · $modelDetail" else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (modelReady) Charaly.ink.muted else Charaly.atmosphere.accent,
                        modifier = Modifier.padding(top = Charaly.space.xxs),
                    )
                }
                CharalyQuietAction(
                    label = Loc.t("action.choose_model"),
                    onClick = onOpenModels,
                    modifier = Modifier.padding(top = Charaly.space.xs),
                )
                CharalyQuietAction(
                    label = Loc.t("settings.build_world"),
                    onClick = onOpenAuthoring,
                    modifier = Modifier.padding(top = Charaly.space.xxs),
                )
            }
        }

        // ---- OFFLINE -----------------------------------------------------
        //
        // Its own section rather than a line in a general section, because "does this work
        // on a plane" is the first question a local-first product gets asked and it deserves
        // a headline.
        item(key = "offline") {
            SettingsGroup(SettingsSection.OFFLINE) {
                Text(
                    text = SettingsPresenter.OFFLINE_BODY,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Charaly.ink.secondary,
                )
            }
        }

        // ---- STORAGE -----------------------------------------------------
        item(key = "storage") {
            SettingsGroup(SettingsSection.STORAGE) {
                Text(
                    text = SettingsPresenter.storageBody(
                        modelCount = installedModelCount,
                        storyCount = storyCount,
                        bytes = installedBytes,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Charaly.ink.secondary,
                )
            }
        }

        // ---- PRIVACY -----------------------------------------------------
        item(key = "privacy") {
            SettingsGroup(SettingsSection.PRIVACY) {
                Text(
                    text = SettingsPresenter.PRIVACY_HEADLINE,
                    style = MaterialTheme.typography.titleLarge,
                    color = Charaly.ink.primary,
                )
                Spacer(Modifier.height(Charaly.space.xs))
                Text(
                    text = SettingsPresenter.PRIVACY_BODY,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Charaly.ink.secondary,
                )
            }
        }

        // ---- DIAGNOSTICS --------------------------------------------------
        //
        // Not behind Developer Mode, and not optional.
        //
        // Every other thing this app claims about a model is an assertion made by some
        // layer: the registry says the file exists, the header says the architecture is
        // supported, the selection says it can generate. None of that proves a token has
        // ever been produced on this device. When somebody's inference does not work - and
        // on a phone, with a specific model, on specific hardware, it often does not - the
        // first useful question is "does it work at all?", and the honest way to answer it
        // is to run it.
        item(key = "diagnostics") {
            SettingsGroup(SettingsSection.DIAGNOSTICS) {
                Text(
                    text = Loc.t("diagnostics.blurb"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Charaly.ink.secondary,
                )
                Spacer(Modifier.height(Charaly.space.sm))
                ModelTestReport(
                    phase = modelTest,
                    subjectName = modelTestSubjectName,
                    onRun = onRunModelTest,
                    onOpenModels = onOpenModels,
                    onClear = onClearModelTest,
                )
            }
        }

        // ---- ADVANCED ----------------------------------------------------
        item(key = "advanced") {
            SettingsGroup(SettingsSection.ADVANCED) {
                SettingSwitch(
                    title = Loc.t("settings.developer_mode"),
                    caption = "Motor durumu, istemler ve olay günlükleri. Varsayılan kapalı.",
                    checked = developerMode,
                    contentDescription = Loc.t("settings.developer_mode"),
                    onCheckedChange = onSetDeveloperMode,
                )
                if (developerMode) {
                    CharalyQuietAction(
                        label = Loc.t("settings.open_inspector"),
                        onClick = onOpenDeveloper,
                        modifier = Modifier.padding(top = Charaly.space.xs),
                    )
                }
            }
        }

        item(key = "version") {
            Text(
                text = Loc.t("settings.charaly_version", versionName),
                style = MaterialTheme.typography.bodySmall,
                color = Charaly.ink.muted,
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = Loc.t("a11y.charaly_version", versionName) },
            )
        }
    }
}

/**
 * One section.
 *
 * A heading and its blurb, then the rows. No card behind it: the sections are separated by
 * space, which is what makes a settings screen read as calm rather than as a form.
 */
@Composable
private fun SettingsGroup(
    section: SettingsSection,
    content: @Composable () -> Unit,
) {
    Column {
        CharalySectionHeader(
            title = section.title,
            micro = true,
            caption = section.blurb,
        )
        Spacer(Modifier.height(Charaly.space.sm))
        content()
    }
}

/**
 * A switch row.
 *
 * ## The switch is labelled
 *
 * A bare toggle next to a title is ambiguous about what it controls - the whole row is the
 * target, and the switch is the state. [contentDescription] is set on the switch itself so
 * a screen reader announces the control and its state rather than just "switch, on".
 */
@Composable
private fun SettingSwitch(
    title: String,
    caption: String,
    checked: Boolean,
    contentDescription: String,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Charaly.space.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = Charaly.ink.primary,
            )
            Text(
                text = caption,
                style = MaterialTheme.typography.bodySmall,
                color = Charaly.ink.muted,
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.semantics { this.contentDescription = contentDescription },
        )
    }
}
