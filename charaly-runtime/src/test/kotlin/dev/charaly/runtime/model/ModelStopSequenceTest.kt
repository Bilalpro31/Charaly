package dev.charaly.runtime.model

import dev.charaly.runtime.model.gguf.GgufMetadata
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PHASE 8 regression guard.
 *
 * Every stop sequence a model gets must be a NON-EMPTY string, and a ChatML template must
 * contribute its own turn-boundary markers.
 *
 * Why this is a real guard and not a style rule: the ChatML branch used to compare
 * `template.contains("")`, which is true for every template, and then added two empty
 * strings to the stop list. The ChatML turn boundary therefore never became a stop
 * condition at all, and with real weights the model ran past its own turn, wrote a bare
 * `user` line and answered it itself (see `BareTurkishCapabilityTest` in the app module).
 * An empty stop string is the worst possible entry: it matches at position 0 of anything,
 * so it is a rule that means nothing while still looking like a configured rule.
 */
class ModelStopSequenceTest {

    /** The real header, minimal: the stop logic reads the chat template and nothing else. */
    private fun metadata(template: String): GgufMetadata {
        val keys = buildMap {
            if (template.isNotEmpty()) put("tokenizer.chat_template", template)
        }
        return GgufMetadata(
            version = 3,
            tensorCount = 0,
            tensorInfoCount = 0,
            keyValues = keys.mapValues { (_, value) ->
                dev.charaly.runtime.model.gguf.GgufValue.Str(value)
            },
        )
    }

    @Test
    fun `no stop sequence is ever blank`() {
        val templates = listOf(
            "{% for m in messages %}<|im_start|>{{ m.role }}\n{{ m.content }}<|im_end|>{% endfor %}",
            "{{ bos_token }}<|start_header_id|>user<|end_header_id|>",
            "<start_of_turn>user\n{{ message }}<end_of_turn>",
            "{{ '[INST] ' + message + ' [/INST]' }}",
            "",
        )

        templates.forEach { template ->
            val stops = ModelPresetLibrary.stopSequences(metadata(template))
            assertTrue(
                "a template produced no stop sequences at all: '${template.take(40)}'",
                stops.isNotEmpty(),
            )
            stops.forEach { stop ->
                assertFalse(
                    "stop sequence must not be blank (template='${template.take(40)}', stops=$stops)",
                    stop.isBlank(),
                )
            }
        }
    }

    @Test
    fun `a ChatML template contributes the ChatML turn boundary`() {
        val stops = ModelPresetLibrary.stopSequences(metadata("{{ bos_token }}<|im_start|>user"))
        val endMarker = "<|im" + "_end|>"
        assertTrue("ChatML template must stop at $endMarker, got $stops", stops.contains(endMarker))
    }

    @Test
    fun `the default list is never blank`() {
        ModelPresetLibrary.defaultStopSequences().forEach { stop ->
            assertFalse("default stop sequence must not be blank", stop.isBlank())
        }
    }
}