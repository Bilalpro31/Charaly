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
import androidx.compose.material3.MaterialTheme
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
import dev.charaly.app.ui.components.CharalyEmptyState
import dev.charaly.app.ui.components.CharalyIconButton
import dev.charaly.app.ui.components.CharalyPill
import dev.charaly.app.ui.components.CharalySectionHeader
import dev.charaly.app.ui.design.Charaly
import dev.charaly.app.ui.design.CharalyShapes
import dev.charaly.runtime.domain.StoryPack

/**
 * AUTHORING.
 *
 * ## The one part of Charaly that is a tool
 *
 * Everything else is a place you go. This is a form you fill in, and it is deliberately
 * styled like one: labelled fields, a review step, explicit save.
 *
 * ## What it creates, and what it does not
 *
 * It creates a *pack*: a set of people, places and rules, with no story in it yet. The pack
 * then appears in Worlds like any other, and stepping into it is a separate decision made
 * through the normal flow.
 *
 * ## Why it is not on the navigation bar
 *
 * Because it is not a destination anybody spends time in. It lives behind Settings, which is
 * where a tool belongs in a product whose primary destinations are worlds and stories.
 */
@Composable
fun AuthoringScreen(
    packs: List<StoryPack>,
    onBack: () -> Unit,
    onSave: (StoryPack) -> Unit,
) {
    var title by remember { mutableStateOf("") }
    var tagline by remember { mutableStateOf("") }
    var premise by remember { mutableStateOf("") }
    var tone by remember { mutableStateOf("") }
    var featured by remember { mutableStateOf(false) }
    var review by remember { mutableStateOf(false) }

    val canSave = title.isNotBlank()

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(Charaly.surface.void),
        contentPadding = PaddingValues(
            start = Charaly.space.gutter,
            end = Charaly.space.gutter,
            top = Charaly.space.xxl,
            bottom = 120.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(Charaly.space.md),
    ) {
        item(key = "back") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CharalyIconButton(
                    icon = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    onClick = onBack,
                )
                Spacer(Modifier.size(Charaly.space.xs))
                Text(
                    text = if (review) "Review" else "Build a world",
                    style = MaterialTheme.typography.headlineLarge,
                    color = Charaly.ink.primary,
                    modifier = Modifier.semantics { heading() },
                )
            }
        }

        if (!review) {
            item(key = "intro") {
                Text(
                    text = "A world is a set of people, places and rules that remember what " +
                        "happened in them. Start with its name and its pitch; the engine will " +
                        "build the rest around whatever you write here.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = Charaly.ink.secondary,
                )
            }

            item(key = "title-field") {
                AuthoringField(
                    label = "Name",
                    value = title,
                    placeholder = "The Last Lighthouse",
                    onChange = { title = it },
                )
            }
            item(key = "tagline-field") {
                AuthoringField(
                    label = "Tagline",
                    value = tagline,
                    placeholder = "The light has been out for nine nights.",
                    onChange = { tagline = it },
                )
            }
            item(key = "premise-field") {
                AuthoringField(
                    label = "Premise",
                    value = premise,
                    placeholder = "One or two sentences on what this world is.",
                    onChange = { premise = it },
                    minHeight = 96.dp,
                )
            }
            item(key = "tone-field") {
                AuthoringField(
                    label = "Tone",
                    value = tone,
                    placeholder = "Salt, weather, stubbornness.",
                    onChange = { tone = it },
                )
            }
            item(key = "featured") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = Charaly.space.xs),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = "Feature this world",
                            style = MaterialTheme.typography.bodyLarge,
                            color = Charaly.ink.primary,
                        )
                        Text(
                            text = "Featured worlds rank above the rest in Worlds.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Charaly.ink.muted,
                        )
                    }
                    Switch(
                        checked = featured,
                        onCheckedChange = { featured = it },
                        modifier = Modifier.semantics {
                            contentDescription = "Feature this world"
                        },
                    )
                }
            }
        } else {
            item(key = "review-card") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(CharalyShapes.soft)
                        .background(Charaly.surface.raised)
                        .padding(Charaly.space.lg),
                ) {
                    Text(
                        text = title.uppercase(),
                        style = MaterialTheme.typography.labelMedium,
                        color = Charaly.atmosphere.accent,
                    )
                    Text(
                        text = title,
                        style = MaterialTheme.typography.headlineMedium,
                        color = Charaly.ink.primary,
                    )
                    if (tagline.isNotBlank()) {
                        Text(
                            text = tagline,
                            style = MaterialTheme.typography.bodyLarge,
                            color = Charaly.ink.secondary,
                            modifier = Modifier.padding(top = Charaly.space.xs),
                        )
                    }
                    if (premise.isNotBlank()) {
                        Text(
                            text = premise,
                            style = MaterialTheme.typography.bodyMedium,
                            color = Charaly.ink.secondary,
                            modifier = Modifier.padding(top = Charaly.space.sm),
                        )
                    }
                    Row(
                        modifier = Modifier.padding(top = Charaly.space.sm),
                        horizontalArrangement = Arrangement.spacedBy(Charaly.space.xs),
                    ) {
                        if (featured) CharalyPill(label = "Featured", selected = true)
                        CharalyPill(label = "${packs.size} worlds on this device")
                    }
                }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(
                start = Charaly.space.gutter,
                end = Charaly.space.gutter,
                top = Charaly.space.sm,
                bottom = Charaly.space.sm,
            ),
    ) {
        CharalyAction(
            label = if (review) "Create" else "Review",
            onClick = {
                if (review) onSave(buildPack(title.trim(), tagline.trim(), premise.trim(), tone.trim(), featured))
                else review = true
            },
            enabled = canSave,
            fillWidth = true,
        )
    }
}

/**
 * Turns the form into a pack.
 *
 * ## The accent is the author's pick from a fixed set
 *
 * A pack's colours are chosen from the shipped identities rather than typed as hex. That is
 * a real constraint and it is deliberate: every identity is a palette that has been checked
 * against true black and against the artwork drawn from it, and a hand-typed hex cannot be.
 * A world that came out looking wrong is a bug in the palette library, not in the author's
 * typing.
 *
 * ## One person, one place, so the world can actually be stepped into
 *
 * A pack with no characters and no locations would pass every display check and produce a
 * story with nobody in it. So the minimum is enforced here, from the smallest thing that
 * could be a world.
 */
private fun buildPack(
    title: String,
    tagline: String,
    premise: String,
    tone: String,
    featured: Boolean,
): StoryPack {
    val identityId = dev.charaly.runtime.domain.CharalyAccent.all.first().first
    return dev.charaly.runtime.pack.PackAuthoring.pack(
        id = "pack-${stableSlug(title)}",
        title = title,
        description = premise.ifBlank { tagline },
        identity = dev.charaly.runtime.pack.PackAuthoring.identity(
            tagline = tagline,
            genres = listOf("Original"),
            coverSeed = stableSlug(title),
            mood = tone,
            primary = dev.charaly.runtime.domain.CharalyAccent.NEUTRAL.primaryHex,
            secondary = dev.charaly.runtime.domain.CharalyAccent.NEUTRAL.secondaryHex,
            accent = dev.charaly.runtime.domain.CharalyAccent.NEUTRAL.accentHex,
            accentIdentity = identityId,
            tone = tone,
            premise = premise,
            featured = featured,
            demo = false,
        ),
        characters = listOf(
            dev.charaly.runtime.domain.CharacterDefinition(
                id = dev.charaly.runtime.domain.CharacterId("protagonist"),
                name = "The one who arrives",
                tagline = "Whichever role you take.",
                accentHex = dev.charaly.runtime.domain.CharalyAccent.NEUTRAL.primaryHex,
                startingLocationId = dev.charaly.runtime.domain.LocationId("the-place"),
            ),
        ),
        locations = listOf(
            dev.charaly.runtime.domain.Location(
                id = dev.charaly.runtime.domain.LocationId("the-place"),
                name = "The place it starts",
                summaryLine = "Where every story in this world opens.",
            ),
        ),
        startTime = dev.charaly.runtime.domain.StoryTime.of(day = 1, hour = 18, minute = 0),
    )
}

/**
 * A stable id from a title.
 *
 * Lower-cased, hyphenated, and reduced to letters and digits, because the id becomes a file
 * name and a URL path segment. A title with an apostrophe must not produce a pack whose id
 * cannot be encoded.
 */
internal fun stableSlug(title: String): String {
    val slug = title
        .lowercase()
        .map { if (it.isLetterOrDigit()) it else '-' }
        .joinToString("")
        .split('-')
        .filter { it.isNotEmpty() }
        .joinToString("-")
    return slug.ifBlank { "world" }.take(48)
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
) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = Charaly.ink.muted,
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = Charaly.space.xxs)
                .clip(CharalyShapes.soft)
                .background(Charaly.surface.raised)
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
                    .semantics { contentDescription = label },
            )
        }
    }
}
