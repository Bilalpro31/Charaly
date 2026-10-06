plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "dev.charaly.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.charaly.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 5
        // 0.8.0 is the release where the model chain stops lying.
        //
        // Four bugs, all of them "the app said something true about a different thing":
        //
        //  * a GGUF downloaded from the Hub was deleted from the registry on the next
        //    launch, because import and download wrote to two different directories and the
        //    launch-time reconciliation only scanned one of them;
        //  * removing a model freed zero bytes, because a File was compared to another File
        //    with `==`, which is reference equality and therefore always false;
        //  * an interrupted import left a truncated .gguf that the next launch registered as
        //    an installed model - imports are now written to a `.part` file and renamed only
        //    after size, magic and storage checks pass;
        //  * a build whose native library was stripped by an ABI split crashed instead of
        //    saying so, because `System.loadLibrary` runs in a static initialiser and a
        //    failure there poisons the class forever.
        //
        // Also in this release: Settings has a Diagnostics section that loads a model and
        // generates a real reply with it, and the Miraculous pack ships 78 original PNGs
        // behind a resolver that picks them by CharacterId and by
        // LocationId + time-of-day + weather.
        versionName = "0.8.0"

        // Charaly ships no model binary. A model arrives one of two real ways: the user
        // downloads a GGUF from the Hugging Face Hub, or imports one they already have.
        // Both go through the same registry and the same install pipeline.
        externalNativeBuild {
            cmake {
                cppFlags += listOf("-std=c++17", "-O3")
                arguments += listOf("-DANDROID_STL=c++_shared")
            }
        }
        ndk {
            // Local inference only needs the mainstream arm ABIs plus x86_64 for
            // emulators. The list is overridable because an ARM64 build host can
            // only cross-compile arm64 (see -PcharalyAbis=arm64-v8a).
            val abis = (project.findProperty("charalyAbis") as String?)
                ?.split(",")
                ?.map(String::trim)
                ?.filter(String::isNotEmpty)
                ?: listOf("arm64-v8a", "armeabi-v7a", "x86_64")
            abiFilters += abis
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += setOf("/META-INF/{AL2.0,LGPL2.1}")
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":charaly-runtime"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.kotlinx.coroutines.android)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // App-layer wiring is unit tested on the JVM: no device, no emulator, no
    // model file. These run with unitTests.isReturnDefaultValues = true.
    //
    // Note: no Robolectric and no MockWebServer. Adding them would pull the build's
    // dependency set out of the offline Gradle cache, and the logic they would test -
    // the download pipeline, the Hub parser, the compatibility classifier - all lives in
    // charaly-runtime, where it is a plain Kotlin/JVM library and is tested directly
    // against scripted fakes. The Android layer's own contribution is a thin adapter,
    // and it is covered by the compile plus the app-side wiring tests.
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
