package dev.charaly.app.inference;

/**
 * Exact mirror of {@code dev.charaly.app.inference.LlamaNative}'s {@code external} surface.
 *
 * <p>Kept in the same package with the same class name so the JNI symbol names - {@code
 * Java_dev_charaly_app_inference_LlamaNative_*} - resolve to the very entry points the APK
 * exports. A harness that used its own class names would be testing a second bridge.
 *
 * <p>The Kotlin declarations live on an {@code object}, so they compile to instance methods;
 * the C++ signatures declare {@code jclass} (a static). JNI resolves a native method by its
 * mangled name alone, and {@code charaly_jni.cpp} never reads that second argument, so one
 * binary serves both - which is itself worth asserting, and is why the harness declares them
 * {@code static}.
 *
 * <p>Callbacks are plain objects exposing {@code invoke} with the erased descriptor the C++
 * looks up ({@code resolve_function1} / {@code resolve_function2}). No kotlin-stdlib is
 * needed: the bridge resolves the method on the callback's own class.
 */
public final class LlamaNative {

    private LlamaNative() {}

    /** {@code kotlin.jvm.functions.Function1} shape. */
    public interface Fn1 {
        Object invoke(Object a);
    }

    /** {@code kotlin.jvm.functions.Function2} shape. */
    public interface Fn2 {
        Object invoke(Object a, Object b);
    }

    public static native boolean nativeAvailable();

    public static native String nativeVersion();

    /**
     * PHASE 10: {@code threadsBatch} sits between {@code threads} and {@code gpuLayers} to
     * match the shipped Kotlin signature exactly - the prompt-evaluation pool is a separate
     * llama.cpp thread pool and a mirror that dropped it would be testing a second bridge.
     */
    public static native long loadModel(
            String path, int contextSize, int threads, int threadsBatch, int gpuLayers,
            Fn2 progressCallback);

    public static native void freeModel(long handle);

    public static native String[] modelMetadata(long handle);

    public static native int contextSize(long handle);

    public static native String generate(
            long handle,
            String prompt,
            int maxTokens,
            float temperature,
            float topP,
            int topK,
            float repeatPenalty,
            long seed,
            String[] stopSequences,
            Fn1 tokenCallback);

    public static native void stopGeneration(long handle);

    public static native String lastError();

    public static native String[] benchmark(long handle, String prompt, int maxTokens);

    public static native String[] availableBackends();
}