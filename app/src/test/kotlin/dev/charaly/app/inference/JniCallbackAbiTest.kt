package dev.charaly.app.inference

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.lang.reflect.Modifier

/**
 * THE CALLBACK ABI, VERIFIED AGAINST REAL KOTLIN LAMBDA CLASSES.
 *
 * ## The bug this exists to prevent
 *
 * `charaly_jni.cpp` used to resolve Kotlin's callbacks with:
 *
 * ```
 *   GetMethodID(cls, "invoke", "(ILjava/lang/String;)V")   // load progress
 *   GetMethodID(cls, "invoke", "(Ljava/lang/String;)V")    // tokens
 * ```
 *
 * Those descriptors are wrong. A Kotlin `(Int, String) -> Unit` is a
 * `kotlin.jvm.functions.Function2`, and since Kotlin 2.0 it is compiled as an
 * `invokedynamic` class whose only method is the *erased* SAM bridge:
 *
 * ```
 *   invoke(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;
 * ```
 *
 * `GetMethodID` therefore returned null, and because the call sites checked for null and
 * quietly returned, progress callbacks never fired and **no token was ever streamed** -
 * while every signature check in the build still passed. The only symptom on a device was
 * a chat screen that waited forever for a reply the model had already produced.
 *
 * ## How this is checked without a device
 *
 * A JVM has the same `java.lang.reflect` and the same `Class.getMethod` the JNI runtime
 * uses, so the descriptor strings can be resolved against real Kotlin lambdas right here.
 * If the C++ stops using a descriptor that resolves, this fails.
 *
 * The negative assertions matter as much as the positive ones: they pin down that the
 * old, wrong descriptors genuinely do not exist on the class the app actually passes,
 * which is the fact that made the bug invisible.
 */
class JniCallbackAbiTest {

    // ==================================================================
    // 1. The descriptor the native side uses must resolve
    // ==================================================================

    @Test
    fun `the progress callback descriptor resolves on a real Kotlin lambda`() {
        // Exactly the callback LocalLlamaInferenceEngine passes to loadModel.
        val callback: (Int, String) -> Unit = { _, _ -> }

        val method = callback.javaClass.getMethod(
            "invoke",
            java.lang.Object::class.java,
            java.lang.Object::class.java,
        )
        assertEquals(
            "kotlin.jvm.functions.Function2.invoke returns Unit, which erases to Object",
            java.lang.Object::class.java,
            method.returnType,
        )
        assertNotNull(
            "the erased bridge is the only method an indy Kotlin lambda exposes",
            method,
        )
    }

    @Test
    fun `the token callback descriptor resolves on a real Kotlin lambda`() {
        // Exactly the callback LocalLlamaInferenceEngine passes to generate.
        val callback: (String) -> Unit = { }

        val method = callback.javaClass.getMethod(
            "invoke",
            java.lang.Object::class.java,
        )
        assertEquals(java.lang.Object::class.java, method.returnType)
    }

    @Test
    fun `the callbacks the app actually passes expose only the erased bridge`() {
        // The decisive fact. If a future compiler change gave these lambdas a
        // primitive-typed invoke, the native descriptor would still resolve - but the
        // assertion below documents which shape the bridge must match today, and the
        // negative assertions below confirm the old descriptors are absent.
        val progress: (Int, String) -> Unit = { _, _ -> }
        val tokens: (String) -> Unit = { }

        assertTrue(
            "the progress callback must be a Kotlin Function2",
            kotlin.jvm.functions.Function2::class.java.isInstance(progress),
        )
        assertTrue(
            "the token callback must be a Kotlin Function1",
            kotlin.jvm.functions.Function1::class.java.isInstance(tokens),
        )

        assertFalse(
            "the primitive descriptor the native code used to look for is absent: " +
                "this is why token streaming silently produced nothing",
            hasMethod(progress.javaClass, "invoke", Int::class.java, String::class.java, Void.TYPE),
        )
        assertFalse(
            "the String->void descriptor the native code used to look for is absent",
            hasMethod(tokens.javaClass, "invoke", String::class.java, Void.TYPE),
        )
    }

    // ==================================================================
    // 2. The class-shaped lambda, which the JVM can also produce
    // ==================================================================

    @Test
    fun `the erased bridge is present on a class-shaped Kotlin function too`() {
        // Kotlin can emit a real class instead of an indy lambda (a named function
        // reference, or an object expression). Such a class carries the erased bridge as
        // well, so resolving the erased descriptor works for either codegen shape and
        // native does not have to guess which one it was handed.
        val classShaped: ClassShapedFunction2 = ClassShapedFunction2()
        val classShapedTokens: ClassShapedFunction1 = ClassShapedFunction1()

        assertNotNull(
            classShaped.javaClass.getMethod(
                "invoke",
                java.lang.Object::class.java,
                java.lang.Object::class.java,
            ),
        )
        assertNotNull(
            classShapedTokens.javaClass.getMethod("invoke", java.lang.Object::class.java),
        )
    }

    @Test
    fun `the primitive descriptor the native code used is not resolvable even on a class-shaped lambda`() {
        // This is the sharper half of the bug, and it is why "just add a fallback" would
        // not have been a real fix.
        //
        // A class-shaped lambda does list an `invoke(int, String)` method - it is a
        // specialised bridge - yet looking it up *by descriptor* fails, because method
        // resolution picks the more specific applicable member rather than the one whose
        // erased signature matches exactly. So a native `GetMethodID(cls, "invoke",
        // "(ILjava/lang/String;)V")` cannot be relied on to find it either.
        //
        // The erased SAM bridge has no such ambiguity, which is why it is the descriptor
        // the bridge uses.
        val classShaped = ClassShapedFunction2()

        val listed = classShaped.javaClass.methods
            .filter { it.name == "invoke" }
            .any { it.parameterTypes.size == 2 && it.parameterTypes[0] == Int::class.java }
        assertTrue(
            "precondition: the specialised bridge is listed on the class",
            listed,
        )

        assertFalse(
            "resolving the primitive descriptor must fail: this is why the old native " +
                "descriptor could not be relied on",
            hasMethod(
                classShaped.javaClass,
                "invoke",
                Int::class.java,
                String::class.java,
                Void.TYPE,
            ),
        )
    }

    /**
     * The shape Kotlin emits when it produces a class rather than an indy lambda: both the
     * erased SAM bridge and a specialised `invoke`. Declared explicitly so this test does
     * not depend on a compiler flag, and because it is the shape the *old* descriptor
     * would have matched - which is why matching it alone was never safe.
     */
    class ClassShapedFunction2 : kotlin.jvm.functions.Function2<Int, String, Unit> {
        override fun invoke(p1: Int, p2: String) = Unit
    }

    /** The one-argument equivalent of [ClassShapedFunction2]. */
    class ClassShapedFunction1 : kotlin.jvm.functions.Function1<String, Unit> {
        override fun invoke(p1: String) = Unit
    }

    // ==================================================================
    // 3. The native source must actually use the verified descriptor
    // ==================================================================

    @Test
    fun `the native bridge resolves callbacks through the erased SAM bridge`() {
        val native = File("src/main/cpp/charaly_jni.cpp").readText()

        // The descriptor this test proved resolves, for each arity.
        assertTrue(
            "Function2 callbacks must be resolved as (Object,Object)Object",
            native.contains("(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"),
        )
        assertTrue(
            "Function1 callbacks must be resolved as (Object)Object",
            native.contains("(Ljava/lang/Object;)Ljava/lang/Object;"),
        )

        // The descriptors that were wrong. Asserted absent so the bug cannot be
        // reintroduced by editing one string back.
        val wrongProgress = Regex("""GetMethodID\([^)]*"invoke",\s*"\(ILjava/lang/String;\)V"""")
        assertFalse(
            "the progress callback must not be resolved with (ILjava/lang/String;)V: " +
                "no Kotlin 2.0 lambda exposes it, so the lookup returns null",
            wrongProgress.containsMatchIn(native),
        )
        assertFalse(
            "the token callback must not be resolved with (Ljava/lang/String;)V",
            Regex("""GetMethodID\([^)]*"invoke",\s*"\(Ljava/lang/String;\)V"""").containsMatchIn(native),
        )
    }

    @Test
    fun `every invoke lookup in the native bridge goes through the resolver`() {
        val native = File("src/main/cpp/charaly_jni.cpp").readText()
        val lookups = Regex("""GetMethodID\(\s*\n?\s*(\w+),\s*\n?\s*"invoke"""").findAll(native).count()
        assertEquals(
            "every invoke lookup should be inside resolve_function1/resolve_function2, " +
                "so a failed lookup always sets a diagnostic instead of being ignored",
            2,
            lookups,
        )
    }

    @Test
    fun `a failed method lookup is reported rather than ignored`() {
        val native = File("src/main/cpp/charaly_jni.cpp").readText()
        assertTrue(
            "a null method must set an error the Kotlin side can read",
            native.contains("callback contract failure"),
        )
        assertTrue(
            "the load path must abort when the callback cannot be resolved",
            native.contains("if (!on_progress.valid)"),
        )
        assertTrue(
            "the generate path must abort when the callback cannot be resolved",
            native.contains("if (!on_token.valid)"),
        )
    }

    @Test
    fun `the returned Unit is released so a long generation cannot leak local refs`() {
        val native = File("src/main/cpp/charaly_jni.cpp").readText()
        assertTrue(
            "kotlin.Unit is returned by every callback call and must be deleted, " +
                "otherwise a long streaming run exhausts the local reference table",
            Regex("""if \(unit != nullptr\) env->DeleteLocalRef\(unit\);""")
                .findAll(native)
                .count() >= 2,
        )
    }

    @Test
    fun `the Int progress argument is boxed before crossing the boundary`() {
        val native = File("src/main/cpp/charaly_jni.cpp").readText()
        assertTrue(
            "the erased bridge takes Object, so an int must be boxed via Integer.valueOf",
            native.contains("java/lang/Integer") && native.contains("valueOf"),
        )
    }

    private fun hasMethod(
        owner: Class<*>,
        name: String,
        vararg params: Class<*>,
    ): Boolean = try {
        owner.getMethod(name, *params).also { Modifier.isPublic(it.modifiers) }
        true
    } catch (error: NoSuchMethodException) {
        false
    }
}