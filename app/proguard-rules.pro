# Charaly keeps everything local: no analytics, no remote endpoints, no reflection
# based model loading, so the default rules are almost enough.

# Keep the runtime's serializable domain model intact. kotlinx.serialization
# generates companion serializers that R8 cannot see are used.
-keepclassmembers class dev.charaly.runtime.** {
    *** Companion;
}
-keepclasseswithmembers class dev.charaly.runtime.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class dev.charaly.runtime.domain.**$$serializer { *; }
-keep,includedescriptorclasses class dev.charaly.runtime.domain.events.**$$serializer { *; }

# The JNI bridge is called from native code only; R8 must not rename or strip it.
-keep class dev.charaly.app.inference.LlamaNative { *; }
-keepclasseswithmembernames class dev.charaly.app.inference.LlamaNative {
    native <methods>;
}

# Event payloads are polymorphic: their class names are the wire format and must
# survive so an old save can still be read after an update.
-keep class dev.charaly.runtime.domain.events.** { *; }
