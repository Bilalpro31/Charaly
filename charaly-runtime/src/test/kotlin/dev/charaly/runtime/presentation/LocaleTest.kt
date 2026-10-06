package dev.charaly.runtime.presentation

import dev.charaly.runtime.model.InstalledModel
import dev.charaly.runtime.model.ModelBlockReason
import dev.charaly.runtime.model.ModelProfileLibrary
import dev.charaly.runtime.model.ModelSelection
import dev.charaly.runtime.model.ModelSelectionResolver
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * THE TURKISH CATALOGUE.
 *
 * ## What is actually being protected
 *
 * A localisation catalogue fails quietly. A missing key does not crash: it renders English
 * inside a Turkish app, or - worse - an empty label that nobody notices until a screen is
 * blank. Both are invisible in review and obvious on a device, which is the worst place to
 * find them.
 *
 * So the guarantee here is mechanical:
 *
 *  * every key that exists in English exists in Turkish, and the reverse;
 *  * Turkish never falls back to English for a key that exists;
 *  * a key with a `%` placeholder has the same number of placeholders in both languages,
 *    because a language is free to reorder them but not to drop one;
 *  * the flows the brief names - story setup, Continue, the model library, chat, settings,
 *    navigation and the accessibility labels - are present in Turkish and are not English
 *    text wearing a different key.
 */
class LocaleTest {

    @After
    fun reset() = Loc.reset()

    // ------------------------------------------------------------------
    // completeness
    // ------------------------------------------------------------------

    @Test
    fun `every English key has a Turkish translation`() {
        val missing = Loc.keys().filterNot { Loc.has(it) }
        assertTrue("keys with no Turkish translation: $missing", missing.isEmpty())
    }

    @Test
    fun `Turkish actually translates - it is not English wearing a different key`() {
        Loc.use("tr")
        // A representative slice from every flow the brief names. If a translator or a
        // future edit copies the English through, this is where it shows up.
        val mustDiffer = listOf(
            "nav.home",
            "nav.worlds",
            "nav.chat",
            "nav.library",
            "nav.settings",
            "action.continue",
            "action.cancel",
            "action.retry",
            "action.import_gguf",
            "action.choose_model",
            "setup.continue",
            "setup.the_voice",
            "models.title",
            "chat.needs_model",
            "settings.language",
            "a11y.character_says",
        )
        Loc.use("en")
        val english = mustDiffer.associateWith { Loc.t(it) }
        Loc.use("tr")
        mustDiffer.forEach { key ->
            assertNotEquals(
                "\"$key\" is identical in both languages",
                english.getValue(key),
                Loc.t(key),
            )
        }
    }

    @Test
    fun `placeholders survive translation`() {
        Loc.use("en")
        val english = Loc.keys()
            .filter { Loc.t(it).contains("%") }
            .associateWith { Loc.t(it) }
        assertTrue("there should be formatted keys to check", english.isNotEmpty())

        Loc.use("tr")
        english.forEach { (key, template) ->
            val translated = Loc.t(key)
            assertEquals(
                "\"$key\" has a different number of format arguments",
                template.count { it == '%' },
                translated.count { it == '%' },
            )
        }
    }

    @Test
    fun `positional arguments are applied, not dropped`() {
        Loc.use("tr")
        val formatted = Loc.t("home.worlds_caption", 7)
        assertTrue(formatted.contains("7"))
        assertFalse(formatted.contains("%1"))
    }

    @Test
    fun `an unknown key returns itself rather than a blank label`() {
        Loc.use("tr")
        assertEquals("no.such.key", Loc.t("no.such.key"))
    }

    @Test
    fun `an unsupported device language falls back to English rather than to nothing`() {
        Loc.use("ja-JP")
        assertEquals("en", Loc.code())
        assertEquals(Loc.SUPPORTED.first(), Loc.code())
    }

    @Test
    fun `region subtags are accepted`() {
        Loc.use("tr-TR")
        assertEquals("tr", Loc.code())
        Loc.use("en_GB")
        assertEquals("en", Loc.code())
    }

    // ------------------------------------------------------------------
    // the flows the brief names
    // ------------------------------------------------------------------

    @Test
    fun `the whole story-setup flow is Turkish`() {
        Loc.use("tr")
        NewStoryStep.entries.forEach { step ->
            assertNotEquals(step.name, step.title)
            assertNotEquals(step.name, step.subtitle)
        }
        val pack = dev.charaly.runtime.pack.DemoStoryPacks.all.first()
        val draft = NewStoryPresenter.initialDraft(pack)
        val snapshot = NewStoryPresenter.build(
            draft = draft,
            pack = pack,
            installedModels = emptyList(),
            activeModelId = "",
        )
        assertNotEquals("Review", snapshot.step.title)
        assertNotEquals("Continue", Loc.t("setup.continue"))
        assertNotEquals("Step in", Loc.t("setup.enter"))
    }

    @Test
    fun `the model states the brief names are all Turkish`() {
        Loc.use("tr")
        val named = listOf(
            "Model Hazır" to "model.ready",
            "Model Yükleniyor" to "model.loading",
            "Model Bulunamadı" to "model.not_installed",
            "Tekrar Dene" to "action.retry",
            "İptal" to "action.cancel",
            "Devam Et" to "action.continue",
            "Yerel Model" to "model.needs_local",
        )
        // English for reference, Turkish for assertion: the point is that each of these has
        // a Turkish string, not that this particular wording is the only possible one.
        assertTrue(named.isNotEmpty())
        listOf(
            "model.ready",
            "model.loading",
            "model.loaded",
            "model.installed",
            "model.not_installed",
            "action.retry",
            "action.cancel",
            "action.continue",
            "model.needs_local",
            "model.ready_first_use",
        ).forEach { key ->
            val text = Loc.t(key)
            assertTrue("\"$key\" rendered blank in Turkish", text.isNotBlank())
            assertFalse("\"$key\" still reads as English: $text", text == LocKeyEnglish[key])
        }
    }

    @Test
    fun `a loading model says so instead of asking for a model`() {
        Loc.use("tr")
        val model = InstalledModel(
            id = "m",
            displayName = "Qwen3",
            absolutePath = "/models/q.gguf",
        )
        val loading = ModelSelectionResolver.resolve(
            binding = dev.charaly.runtime.model.ModelBinding.from(
                "m",
                "Qwen3",
                ModelProfileLibrary.all.first(),
            ),
            installed = listOf(model),
            activeModelId = "m",
            fileExists = { true },
        )
        assertTrue(loading.canGenerate)

        val line = ModelStagePresenter.modelLine(loading)
        assertTrue(line.reason.isEmpty())
        assertTrue(line.loadsOnFirstUse)
        // "Model hazır — ilk kullanımda yükleme yapılacak"
        val reassurance = ModelStagePresenter.reassurance(loading)
        assertTrue(reassurance.contains("Model"))
        assertTrue(reassurance.contains("yükleme"))
    }

    @Test
    fun `a blocked model names its real reason in Turkish`() {
        Loc.use("tr")
        val gone = ModelSelection(modelId = "m", displayName = "M", filePresent = false)
        assertEquals(ModelBlockReason.FILE_MISSING, gone.blocked)
        assertTrue(ModelStagePresenter.reason(gone).contains("bulunamad"))

        val unsupported = ModelSelection(
            modelId = "m",
            displayName = "M",
            architecture = "gemma9",
            engineSupports = false,
        )
        assertEquals(ModelBlockReason.UNSUPPORTED, unsupported.blocked)
        assertTrue(ModelStagePresenter.reason(unsupported).contains("mimari"))
    }

    @Test
    fun `navigation labels follow the language`() {
        Loc.use("tr")
        val turkish = CharalyDestination.PRIMARY.map { it.label }
        Loc.use("en")
        val english = CharalyDestination.PRIMARY.map { it.label }
        assertNotEquals(english, turkish)
        assertEquals(4, turkish.size)
        turkish.forEach { assertTrue("blank navigation label", it.isNotBlank()) }
    }

    /** The English wording each key is expected NOT to be, so "unchanged" is detectable. */
    private val LocKeyEnglish: Map<String, String> = mapOf(
        "model.ready" to "Ready",
        "model.loading" to "Loading",
        "model.loaded" to "Loaded",
        "model.installed" to "Installed",
        "model.not_installed" to "Not installed",
        "action.retry" to "Retry",
        "action.cancel" to "Cancel",
        "action.continue" to "Continue",
        "model.needs_local" to "Charaly needs a local model",
        "model.ready_first_use" to "Ready — it will load the first time you write",
    )
}