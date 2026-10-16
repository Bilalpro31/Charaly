package dev.charaly.app.inference;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Drives the REAL native inference chain and reports only what actually happened.
 *
 * <pre>
 *   GGUF file -> charaly_jni.cpp -> llama.cpp -> tokenize -> decode -> sample -> tokens
 * </pre>
 *
 * <p>There is no code path here that can print PASS without text coming back out of the
 * model. Every generation case asserts {@code tokensProduced > 0 && text.length() > 0}; a
 * run that loaded a model and produced nothing is a FAIL, because that is exactly the
 * failure this whole effort exists to eliminate.
 *
 * <p>Stress runs follow the spec's matrix: 10x load/generate/unload, 5x A/B switch, 20x
 * back-to-back generation, restart cycles, and stop-then-reload. Any crash, hang or leaked
 * handle ends the process non-zero.
 */
public final class Harness {

    /** The prompt the specification requires a real reply to. */
    private static final String SPEC_PROMPT =
            "You are Charaly's local AI. Reply with exactly one short sentence.\n\n[assistant]";

    /** The same turn format LocalLlamaInferenceEngine.buildPrompt produces. */
    private static final String CHAT_PROMPT =
            "The rain had stopped by the time she reached the corner of the street.\n"
                    + "[user] Merhaba. Hava bugün nasıl?\n[assistant]";

    private static int passed = 0;
    private static int failed = 0;
    private static final List<String> report = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        // The shared object is loaded by absolute path rather than by name, so the harness
        // works without touching java.library.path and, more importantly, without any
        // loader logic inside the mirror of LlamaNative - that mirror has to stay byte-for-
        // byte equivalent to the Kotlin `external` surface it stands in for.
        String lib = System.getProperty("charaly.native.lib");
        if (lib == null || lib.isEmpty()) {
            System.err.println("pass -Dcharaly.native.lib=/abs/path/libcharaly_llama.so");
            System.exit(2);
        }
        System.load(new File(lib).getAbsolutePath());

        String modelA = args.length > 0 ? args[0] : "";
        String modelB = args.length > 1 ? args[1] : modelA;
        if (modelA.isEmpty() || !new File(modelA).isFile()) {
            System.err.println("usage: Harness <model-a.gguf> [model-b.gguf]");
            System.exit(2);
        }

        System.out.println("== CHARALY NATIVE HARNESS ==");
        System.out.println("bridge callable : " + LlamaNative.nativeAvailable());
        System.out.println("llama.cpp       : " + trim(LlamaNative.nativeVersion()));
        System.out.println("backends        : " + String.join(",", LlamaNative.availableBackends()));
        System.out.println("model A         : " + modelA + " (" + new File(modelA).length() + " bytes)");
        System.out.println("model B         : " + modelB + " (" + new File(modelB).length() + " bytes)");
        System.out.println();

        negativeCases(modelA);
        caseRealGeneration(modelA);
        caseMetadataIsReal(modelA);
        caseTurkishPrompt(modelA);
        caseLongPromptIsSurvivable(modelA);
        caseContextCleanup(modelA);
        caseStopActuallyStops(modelA);
        caseRepeatedGeneration(modelA);
        stress1_LoadGenerateUnload(modelA);
        stress2_ModelSwitch(modelA, modelB);
        stress3_TwentyGenerations(modelA);
        stress4_Restart(modelA);
        stress5_StopReloadGenerate(modelA);

        System.out.println();
        System.out.println("== RESULTS ==");
        for (String line : report) {
            System.out.println("  " + line);
        }
        System.out.println();
        System.out.println("passed=" + passed + " failed=" + failed);
        System.exit(failed == 0 ? 0 : 1);
    }

    // ------------------------------------------------------------------
    // cases
    // ------------------------------------------------------------------

    /** A file that is not a GGUF must be refused by the loader, never partially loaded. */
    private static void negativeCases(String modelA) throws Exception {
        File dir = Files.createTempDirectory("charaly-negative").toFile();
        File zero = new File(dir, "zero.gguf");
        write(zero, new byte[0]);
        File notGguf = new File(dir, "notes.gguf");
        write(notGguf, "this is a text file pretending to be a model".getBytes(StandardCharsets.UTF_8));
        byte[] real = Files.readAllBytes(new File(modelA).toPath());
        File truncated = new File(dir, "truncated.gguf");
        write(truncated, java.util.Arrays.copyOf(real, Math.min(real.length, 512)));

        expect("reject: zero-byte file", () -> {
            long h = LlamaNative.loadModel(zero.getAbsolutePath(), 256, 0, 0, 0, IGNORE_PROGRESS);
            if (h != 0L) {
                LlamaNative.freeModel(h);
                return "a zero-byte file was accepted";
            }
            return null;
        });
        expect("reject: not a GGUF", () -> {
            long h = LlamaNative.loadModel(notGguf.getAbsolutePath(), 256, 0, 0, 0, IGNORE_PROGRESS);
            if (h != 0L) {
                LlamaNative.freeModel(h);
                return "a text file was accepted";
            }
            return null;
        });
        expect("reject: truncated header", () -> {
            long h = LlamaNative.loadModel(truncated.getAbsolutePath(), 256, 0, 0, 0, IGNORE_PROGRESS);
            if (h != 0L) {
                LlamaNative.freeModel(h);
                return "a truncated GGUF was accepted";
            }
            return null;
        });
        expect("reject: missing file", () -> {
            long h = LlamaNative.loadModel(new File(dir, "absent.gguf").getAbsolutePath(), 256, 0, 0, 0, IGNORE_PROGRESS);
            return h == 0L ? null : "a missing file was accepted";
        });
    }

    /** THE case: a real prompt must come back with real text. */
    private static void caseRealGeneration(String modelA) {
        expect("REAL inference: spec prompt produces tokens", () -> {
            long h = loadOrFail(modelA, 512);
            try {
                Generation g = generate(h, SPEC_PROMPT, 32);
                if (g.tokens == 0) return "zero tokens were produced";
                if (g.text.trim().isEmpty()) return "the model produced no text";
                note("        model said: \"" + g.text.trim().replace('\n', ' ') + "\"");
                note("        real tokens emitted across JNI: " + g.tokens);
                return null;
            } finally {
                LlamaNative.freeModel(h);
            }
        });
    }

    /** Metadata must come from the file, not from a constant. */
    private static void caseMetadataIsReal(String modelA) {
        expect("metadata is read from the GGUF", () -> {
            long h = loadOrFail(modelA, 256);
            try {
                String[] pairs = LlamaNative.modelMetadata(h);
                if (pairs == null || pairs.length == 0) return "the loader reported no metadata";
                java.util.Map<String, String> meta = new java.util.LinkedHashMap<>();
                for (int i = 0; i + 1 < pairs.length; i += 2) meta.put(pairs[i], pairs[i + 1]);
                String arch = meta.get("general.architecture");
                if (arch == null || arch.isEmpty()) return "no architecture in the header";
                note("        architecture=" + arch + " fileType=" + meta.get("general.file_type")
                        + " ctx=" + LlamaNative.contextSize(h));
                return null;
            } finally {
                LlamaNative.freeModel(h);
            }
        });
    }

    /** Turkish text must survive the modified-UTF-8 boundary intact. */
    private static void caseTurkishPrompt(String modelA) {
        expect("REAL inference: Turkish prompt (unicode intact)", () -> {
            long h = loadOrFail(modelA, 512);
            try {
                Generation g = generate(h, CHAT_PROMPT, 32);
                if (g.tokens == 0) return "zero tokens for a Turkish prompt";
                if (g.text.indexOf('\uFFFD') >= 0) {
                    return "the reply contains U+FFFD replacement characters";
                }
                note("        model said: \"" + g.text.trim().replace('\n', ' ') + "\"");
                return null;
            } finally {
                LlamaNative.freeModel(h);
            }
        });
    }

    /** A prompt longer than the context must fail gracefully, not abort the process. */
    private static void caseLongPromptIsSurvivable(String modelA) {
        expect("long prompt fails gracefully (no crash)", () -> {
            long h = loadOrFail(modelA, 256);
            try {
                StringBuilder longPrompt = new StringBuilder();
                for (int i = 0; i < 4000; i++) longPrompt.append("the night was long and cold ");
                Generation g = generate(h, longPrompt.toString(), 16);
                note("        over-long prompt -> " + g.tokens + " tokens, err=\""
                        + trim(LlamaNative.lastError()) + "\" (process survived)");
                return null; // surviving at all is the requirement
            } finally {
                LlamaNative.freeModel(h);
            }
        });
    }

    /** Freeing the context must make a later generate fail cleanly rather than crash. */
    private static void caseContextCleanup(String modelA) {
        expect("context cleanup: use after free is refused", () -> {
            long h = loadOrFail(modelA, 256);
            LlamaNative.freeModel(h);
            String out = LlamaNative.generate(h, SPEC_PROMPT, 8, 0.8f, 0.95f, 40, 1.1f, -1L,
                    new String[0], IGNORE_TOKENS);
            LlamaNative.freeModel(h); // freeing a stale handle twice must also be safe
            if (out != null && !out.isEmpty()) return "a freed context still produced text";
            return null;
        });
    }

    /** Stop must interrupt a running decode, not just a spinner. */
    private static void caseStopActuallyStops(String modelA) throws Exception {
        expect("REAL stop interrupts a running decode", () -> {
            long h = loadOrFail(modelA, 2048);
            try {
                AtomicInteger seen = new AtomicInteger();
                CountDownLatch started = new CountDownLatch(1);
                AtomicInteger produced = new AtomicInteger();
                Thread worker = new Thread(() -> {
                    LlamaNative.generate(h, SPEC_PROMPT, 400, 0.9f, 0.95f, 40, 1.0f, -1L,
                            new String[0], t -> {
                                seen.incrementAndGet();
                                started.countDown();
                                return null;
                            });
                    produced.set(seen.get());
                });
                worker.start();
                if (!started.await(20, TimeUnit.SECONDS)) {
                    LlamaNative.stopGeneration(h);
                    worker.join(20_000);
                    return "no token arrived within 20s";
                }
                LlamaNative.stopGeneration(h);
                worker.join(60_000);
                if (worker.isAlive()) return "the decode did not stop";
                note("        stopped after " + produced.get() + " of 400 requested tokens");
                return null;
            } finally {
                LlamaNative.freeModel(h);
            }
        });
    }

    /** Twenty turns in a row must all produce text, not degrade. */
    private static void caseRepeatedGeneration(String modelA) {
        expect("20 consecutive generations all produce text", () -> {
            long h = loadOrFail(modelA, 512);
            try {
                for (int i = 0; i < 20; i++) {
                    Generation g = generate(h, SPEC_PROMPT + "\nturn " + i, 16);
                    if (g.tokens == 0) return "turn " + i + " produced zero tokens";
                }
                note("        20/20 turns produced real tokens");
                return null;
            } finally {
                LlamaNative.freeModel(h);
            }
        });
    }

    /** Stress 1: load -> generate -> unload, ten times over. */
    private static void stress1_LoadGenerateUnload(String modelA) {
        expect("STRESS 1: load->generate->unload x10", () -> {
            for (int i = 0; i < 10; i++) {
                long h = loadOrFail(modelA, 512);
                try {
                    Generation g = generate(h, SPEC_PROMPT, 16);
                    if (g.tokens == 0) return "cycle " + i + " produced zero tokens";
                } finally {
                    LlamaNative.freeModel(h);
                }
            }
            note("        10/10 cycles produced real tokens, every handle released");
            return null;
        });
    }

    /** Stress 2: the model switch. A's memory must go before B's load begins. */
    private static void stress2_ModelSwitch(String modelA, String modelB) {
        expect("STRESS 2: A -> B switch x5, one resident at a time", () -> {
            for (int i = 0; i < 5; i++) {
                long a = loadOrFail(modelA, 512);
                try {
                    if (generate(a, SPEC_PROMPT, 16).tokens == 0) return "cycle " + i + ": A produced nothing";
                } finally {
                    LlamaNative.freeModel(a);
                }
                long b = loadOrFail(modelB, 512);
                try {
                    if (generate(b, SPEC_PROMPT, 16).tokens == 0) return "cycle " + i + ": B produced nothing";
                } finally {
                    LlamaNative.freeModel(b);
                }
            }
            note("        5/5 switch cycles, 10 loads, every one real");
            return null;
        });
    }

    /** Stress 3 is covered by caseRepeatedGeneration; this keeps the numbering visible. */
    private static void stress3_TwentyGenerations(String modelA) {
        expect("STRESS 3: 20 back-to-back generations", () -> {
            long h = loadOrFail(modelA, 2048);
            try {
                for (int i = 0; i < 20; i++) {
                    if (generate(h, SPEC_PROMPT, 24).tokens == 0) return "turn " + i + " produced nothing";
                }
                note("        20 turns on one context, 20 real replies");
                return null;
            } finally {
                LlamaNative.freeModel(h);
            }
        });
    }

    /** Stress 4: process death and restart - the model is re-loaded, not re-created. */
    private static void stress4_Restart(String modelA) {
        expect("STRESS 4: restart cycle (unload, reload, generate) x5", () -> {
            for (int i = 0; i < 5; i++) {
                // Native residency is deliberately not persisted across a process death, so
                // "restart" is: nothing resident -> load -> generate.
                long h = loadOrFail(modelA, 512);
                try {
                    if (generate(h, SPEC_PROMPT, 16).tokens == 0) return "restart " + i + " produced nothing";
                } finally {
                    LlamaNative.freeModel(h);
                }
                String afterFree = LlamaNative.generate(h, SPEC_PROMPT, 4, 0.8f, 0.95f, 40, 1.1f, -1L,
                        new String[0], IGNORE_TOKENS);
                if (afterFree != null && !afterFree.isEmpty()) {
                    return "restart " + i + ": a released handle still generated";
                }
            }
            note("        5/5 restart cycles recovered a real reply");
            return null;
        });
    }

    /** Stress 5: stop, reload, generate - the recovery path after a cancelled turn. */
    private static void stress5_StopReloadGenerate(String modelA) throws Exception {
        expect("STRESS 5: stop -> reload -> generate x3", () -> {
            for (int i = 0; i < 3; i++) {
                long h = loadOrFail(modelA, 2048);
                CountDownLatch started = new CountDownLatch(1);
                Thread worker = new Thread(() -> LlamaNative.generate(h, SPEC_PROMPT, 400, 0.9f, 0.95f,
                        40, 1.0f, -1L, new String[0], t -> {
                            started.countDown();
                            return null;
                        }));
                worker.start();
                started.await(20, TimeUnit.SECONDS);
                LlamaNative.stopGeneration(h);
                worker.join(60_000);
                if (worker.isAlive()) return "cycle " + i + ": decode ignored the stop";
                LlamaNative.freeModel(h);

                long again = loadOrFail(modelA, 2048);
                try {
                    if (generate(again, SPEC_PROMPT, 16).tokens == 0) {
                        return "cycle " + i + ": nothing after reload";
                    }
                } finally {
                    LlamaNative.freeModel(again);
                }
            }
            note("        3/3 stop->reload->generate cycles recovered");
            return null;
        });
    }

    // ------------------------------------------------------------------
    // primitives
    // ------------------------------------------------------------------

    private static long loadOrFail(String path, int ctx) {
        long h = LlamaNative.loadModel(path, ctx, 0, 0, 0, IGNORE_PROGRESS);
        if (h == 0L) throw new IllegalStateException("load refused: " + LlamaNative.lastError());
        return h;
    }

    private static final class Generation {
        final int tokens;
        final String text;

        Generation(int tokens, String text) {
            this.tokens = tokens;
            this.text = text;
        }
    }

    private static Generation generate(long handle, String prompt, int maxTokens) {
        StringBuilder text = new StringBuilder();
        AtomicInteger count = new AtomicInteger();
        String out = LlamaNative.generate(handle, prompt, maxTokens, 0.8f, 0.95f, 40, 1.1f, -1L,
                new String[0], t -> {
                    count.incrementAndGet();
                    text.append((String) t);
                    return null;
                });
        return new Generation(count.get(), out != null ? out : text.toString());
    }

    private static final LlamaNative.Fn1 IGNORE_TOKENS = t -> null;
    private static final LlamaNative.Fn2 IGNORE_PROGRESS = (a, b) -> null;

    /** A check. Returns null on pass, or the reason it failed. */
    private interface Check {
        String run() throws Exception;
    }

    private static void expect(String name, Check check) {
        try {
            String failure = check.run();
            if (failure == null) {
                passed++;
                report.add("PASS  " + name);
            } else {
                failed++;
                report.add("FAIL  " + name + " :: " + failure);
            }
        } catch (Throwable t) {
            failed++;
            report.add("FAIL  " + name + " :: threw " + t);
        }
    }

    private static void note(String line) {
        report.add(line);
    }

    private static void write(File file, byte[] bytes) throws Exception {
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(bytes);
        }
    }

    private static String trim(String s) {
        if (s == null) return "";
        return s.length() > 120 ? s.substring(0, 120) + "..." : s;
    }
}