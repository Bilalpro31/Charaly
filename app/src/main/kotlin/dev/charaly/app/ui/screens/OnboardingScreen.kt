package dev.charaly.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import dev.charaly.runtime.presentation.Loc
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.charaly.app.ui.art.PackArt
import dev.charaly.app.ui.components.CharalyAction
import dev.charaly.app.ui.components.CharalyQuietAction
import dev.charaly.app.ui.design.Charaly
import dev.charaly.app.ui.design.CharalyAtmosphere
import dev.charaly.app.ui.design.CharalyShapes
import dev.charaly.runtime.domain.PackArtwork

/**
 * FIRST LAUNCH.
 *
 * ## Three sentences, and one promise each
 *
 * ```
 *   Your stories stay with you.        files on this device
 *   Worlds remember.                   people, places and rules
 *   The model runs here.               no server, ever
 * ```
 *
 * That is the whole onboarding. A first-run carousel that explains features is a
 * confession that the product needed explaining, and the previous version's third page
 * mentioned llama.cpp by name, which is a fact about the implementation rather than a
 * promise to the person holding the phone.
 *
 * ## Art, not illustration
 *
 * Each page draws a generated composition in Charaly's own neutral atmosphere. The colour
 * on this screen belongs to Charaly, because there is no world on show yet - a pack's
 * accent arrives when a pack is on show, and not before.
 */
private data class OnboardingPage(
    val seed: String,
    val title: String,
    val body: String,
)

@Composable
fun OnboardingScreen(onFinish: () -> Unit) {
    val pages = remember {
        listOf(
            OnboardingPage(
                seed = "charaly-onboarding-stories",
                title = Loc.t("onboarding.stories_title"),
                body = "Sohbetler, anılar ve dünyalar bu cihazınızdaki dosyalardır. " +
                    "Yazdığınız hiçbir şey hiçbir yere gönderilmez.",
            ),
            OnboardingPage(
                seed = "charaly-onboarding-worlds",
                title = Loc.t("onboarding.worlds_title"),
                body = "Bir dünya; insanları, mekânları ve olanı hatırlayan kuralları barındırır. " +
                    "Böylece bir hikâye, planlamadığınız bir yere gidebilir.",
            ),
            OnboardingPage(
                seed = "charaly-onboarding-model",
                title = Loc.t("onboarding.model_title"),
                body = "Yanıtlar telefonunuzdaki bir modelden gelir. Bir tane indirin ya da " +
                    "kendi modelinizi getirin; hiçbir bağlantı olmadan her şey çalışmaya devam eder.",
            ),
        )
    }

    var index by remember { mutableIntStateOf(0) }
    val page = pages[index.coerceIn(0, pages.lastIndex)]
    val isLast = index >= pages.lastIndex

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Charaly.surface.void)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(Charaly.space.gutter),
    ) {
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            PackArt(
                artwork = PackArtwork.generated(seed = page.seed),
                atmosphere = CharalyAtmosphere.Neutral,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(320.dp)
                    .clip(CharalyShapes.soft)
                    .background(
                        Brush.verticalGradient(CharalyAtmosphere.Neutral.wash()),
                    ),
            )
        }

        Spacer(Modifier.height(Charaly.space.xl))

        Text(
            text = page.title,
            style = MaterialTheme.typography.displaySmall,
            color = Charaly.ink.primary,
            modifier = Modifier.semantics { heading() },
        )
        Spacer(Modifier.height(Charaly.space.xs))
        Text(
            text = page.body,
            style = MaterialTheme.typography.bodyLarge,
            color = Charaly.ink.secondary,
        )

        Spacer(Modifier.height(Charaly.space.lg))

        Row(verticalAlignment = Alignment.CenterVertically) {
            // Three dots rather than "1 of 3": the number is the least interesting fact
            // about being on the first of three screens.
            Row(horizontalArrangement = Arrangement.spacedBy(Charaly.space.xs)) {
                pages.indices.forEach { i ->
                    Box(
                        Modifier
                            .size(width = if (i == index) 20.dp else 6.dp, height = 6.dp)
                            .clip(CharalyShapes.pill)
                            .background(
                                if (i == index) {
                                    Charaly.atmosphere.accent
                                } else {
                                    Charaly.surface.overlay
                                },
                            ),
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            if (!isLast) {
                CharalyQuietAction(
                    label = "Geç",
                    onClick = { index = pages.lastIndex },
                )
            }
        }

        Spacer(Modifier.height(Charaly.space.md))

        CharalyAction(
            label = if (isLast) "İçeri adım at" else "Devam Et",
            onClick = {
                if (isLast) onFinish() else index += 1
            },
            fillWidth = true,
        )
    }
}
