import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.room)
}

// Release signing (v1.0.40): keystore lives OUTSIDE the repo. Path of the properties file comes from
// VERDROID_KEYSTORE_PROPS (default /home/box/secrets/verdroid-keystore.properties), or env vars
// VERDROID_STORE_FILE / VERDROID_STORE_PASSWORD / VERDROID_KEY_ALIAS / VERDROID_KEY_PASSWORD.
// Without either, release builds fall back to the debug key so others/CI can still build.
val releaseSigning: Map<String, String>? = run {
    val f = file(System.getenv("VERDROID_KEYSTORE_PROPS") ?: "/home/box/secrets/verdroid-keystore.properties")
    val props = Properties()
    if (f.isFile) f.inputStream().use { props.load(it) }
    fun v(prop: String, env: String) = props.getProperty(prop) ?: System.getenv(env)
    val m = mapOf(
        "storeFile" to v("storeFile", "VERDROID_STORE_FILE"),
        "storePassword" to v("storePassword", "VERDROID_STORE_PASSWORD"),
        "keyAlias" to v("keyAlias", "VERDROID_KEY_ALIAS"),
        "keyPassword" to v("keyPassword", "VERDROID_KEY_PASSWORD"),
    )
    if (m.values.all { !it.isNullOrBlank() } && file(m["storeFile"]!!).isFile) m.mapValues { it.value!! } else null
}
// Review #14: say it loudly when a release build would be signed with the debug key (never publish such an APK).
if (releaseSigning == null && gradle.startParameter.taskNames.any { it.contains("Release", ignoreCase = true) })
    logger.warn("\n" + "!".repeat(72) + "\nWARNING: release signing NOT configured -> this RELEASE APK is signed with the DEBUG key.\n" +
        "Do NOT publish it. Set VERDROID_KEYSTORE_PROPS or the VERDROID_* env vars.\n" + "!".repeat(72))

android {
    namespace = "com.verdroid.app"
    compileSdk = 35
    buildToolsVersion = "35.0.0"

    defaultConfig {
        applicationId = "com.verdroid.app"
        minSdk = 30
        targetSdk = 35
        versionCode = 128
        versionName = "1.0.75"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }
        // On-device LLM (llama.cpp, CPU only): arm64 phones only.
        ndk { abiFilters += "arm64-v8a" }
        externalNativeBuild {
            cmake {
                arguments += listOf("-DANDROID_STL=c++_static", "-DCMAKE_BUILD_TYPE=Release")
                cppFlags += listOf("-O2")
            }
        }
    }
    ndkVersion = "27.1.12297006"
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    signingConfigs {
        if (releaseSigning != null) create("release") {
            storeFile = file(releaseSigning.getValue("storeFile"))
            storePassword = releaseSigning.getValue("storePassword")
            keyAlias = releaseSigning.getValue("keyAlias")
            keyPassword = releaseSigning.getValue("keyPassword")
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = if (releaseSigning != null) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
        aidl = true // Shizuku UserService (IShellService.aidl)
    }
    packaging {
        // llama.cpp + Vulkan shaders (~37 MB .so) compress to ~12 MB; extracted once at install.
        jniLibs { useLegacyPackaging = true }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            // JGit ships OSGi/Eclipse metadata that can clash with other jars.
            excludes += listOf("/META-INF/DEPENDENCIES", "/META-INF/eclipse.inf", "/about.html")
            pickFirsts += listOf("plugin.properties")
        }
    }
    // APK file name: Verdroid-v<version>.apk (release), Verdroid-v<version>-<buildType>.apk otherwise
    applicationVariants.all {
        outputs.all {
            (this as com.android.build.gradle.internal.api.BaseVariantOutputImpl).outputFileName =
                if (buildType.name == "release") "Verdroid-v${versionName}.apk" else "Verdroid-v${versionName}-${buildType.name}.apk"
        }
    }
    // v1.0.12: Room DAO + migration tests run on the JVM via Robolectric (old schemas read from /schemas).
    testOptions.unitTests.isIncludeAndroidResources = true
    lint {
        abortOnError = true
        checkReleaseBuilds = false
    }
}

room {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.webkit) // drop the X-Requested-With header in the login WebView (Reddit blanks WebView logins)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.savedstate.ktx)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.material.color.utilities)
    implementation(libs.androidx.compose.material.icons.core)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.navigation.compose)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.security.crypto)

    implementation(libs.retrofit)
    implementation(libs.retrofit.kotlinx.serialization)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.jsoup) // web_search result parsing + web_fetch Markdown extraction
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)
    implementation(libs.jgit)
    // Google LiteRT-LM: second on-device engine for .litertlm models (GPU via OpenCL, CPU fallback).
    implementation(libs.litertlm.android)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    // Layout screenshot tests (Robolectric native graphics + Compose UI test)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}
