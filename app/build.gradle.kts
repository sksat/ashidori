import java.net.URI

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.scalaAndroid)
}

scala.scalaVersion = libs.versions.scala.get()

// Optional: a sync server the URL field starts with, and whose https
// registration links (https://<host>/pair) open the app (App Links). Set it
// for a personal build outside the repository, e.g. in
// ~/.gradle/gradle.properties (`ashidori.defaultServer=https://<host>`) or
// with `-Pashidori.defaultServer=...`. Without it the URL field starts empty
// and only the ashidori://pair links open the app.
val defaultServerHost: String? = (findProperty("ashidori.defaultServer") as String?)?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() }?.let { s ->
    val u = URI(s)
    require(u.scheme == "https" && !u.host.isNullOrEmpty() && u.port == -1 && u.rawPath.isNullOrEmpty() &&
        u.rawQuery == null && u.rawFragment == null && u.rawUserInfo == null) {
        "ashidori.defaultServer must be https://<host> without port or path, got $s"
    }
    u.host.lowercase()
}

// Optional: the release signing key, kept outside the repository, e.g. in
// ~/.gradle/gradle.properties:
//   ashidori.release.storeFile=/path/to/release.p12
//   ashidori.release.storePassword=...
//   ashidori.release.keyAlias=ashidori
// Without it, release builds are signed with the debug key (for local testing).
val releaseStoreFile: String? = (findProperty("ashidori.release.storeFile") as String?)?.takeIf { it.isNotBlank() }

android {
    namespace = "net.sksat.ashidori"
    compileSdk = 37

    signingConfigs {
        if (releaseStoreFile != null) create("release") {
            storeFile = file(releaseStoreFile)
            storePassword = findProperty("ashidori.release.storePassword") as String?
            keyAlias = findProperty("ashidori.release.keyAlias") as String? ?: "ashidori"
            keyPassword = storePassword
        }
    }

    defaultConfig {
        applicationId = "net.sksat.ashidori"
        // Android 13: OPPO Reno5 A's last OS. Scala 3.8+ lazy vals need VarHandle (API 33+).
        minSdk = 33
        targetSdk = 37
        versionCode = 25
        versionName = "0.24.0"
        // Pixel 8a is arm64; x86_64 is for the emulator.
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
        buildConfigField("String", "DEFAULT_SERVER_URL", "\"${defaultServerHost?.let { "https://$it" } ?: ""}\"")
        defaultServerHost?.let { manifestPlaceholders["pairHost"] = it }
    }
    buildFeatures { buildConfig = true }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    packaging {
        resources {
            excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "**/*.tasty")
        }
    }
}

// The App Links filter needs a host: merged only when ashidori.defaultServer is set.
// The license texts of the map's fonts, icons and layers (tools/licenses/, the
// same texts the tools put into the downloaded maps) are assets, for データの出典.
androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addStaticSourceDirectory(rootProject.file("tools/licenses").path)
        if (defaultServerHost != null) variant.sources.manifests.addStaticManifestFile("src/applinks/AndroidManifest.xml")
    }
}

dependencies {
    implementation(project(":core"))
    implementation(libs.androidx.core)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.maplibre)
    // MapLibre's HTTP client with the app's interceptor (Net.install): the version MapLibre brings.
    implementation(libs.okhttp)
    implementation(libs.work.runtime)
    // Scanning registration links (QR codes); decoded on the device with ZXing (core).
    implementation(libs.camera.camera2)
    implementation(libs.camera.lifecycle)
    implementation(libs.camera.view)
}

// The offline basemap is downloaded on first run (tools/basemap/package.sh),
// not bundled. Older checkouts generated it into the assets: refuse to ship it.
val staleBasemap = file("src/main/assets/basemap")
tasks.named("preBuild") {
    doFirst {
        if (staleBasemap.exists()) {
            throw GradleException("$staleBasemap is no longer bundled: move it to tools/basemap/out/ (see README「簡易地図」)")
        }
    }
}

tasks.withType<ScalaCompile>().configureEach {
    // https://github.com/gradle/gradle/issues/36466
    scalaCompileOptions.additionalParameters?.addAll(listOf("-deprecation", "-feature", "-Xunchecked-java-output-version:17"))
}
