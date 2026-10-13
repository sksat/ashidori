plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.scalaAndroid)
}

scala.scalaVersion = libs.versions.scala.get()

android {
    namespace = "net.sksat.ashidori"
    compileSdk = 37

    defaultConfig {
        applicationId = "net.sksat.ashidori"
        // Android 13: OPPO Reno5 A's last OS. Scala 3.8+ lazy vals need VarHandle (API 33+).
        minSdk = 33
        targetSdk = 37
        versionCode = 14
        versionName = "0.14.0"
        // Pixel 8a is arm64; x86_64 is for the emulator.
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }

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
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    androidResources {
        // The offline basemap's tiles are gzip-compressed already; stored
        // uncompressed, the archives copy out fast (and can be read in
        // place once MapLibre reads byte ranges from assets).
        noCompress += "pmtiles"
    }
    packaging {
        resources {
            excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "**/*.tasty")
        }
    }
}

dependencies {
    implementation(project(":core"))
    implementation(libs.androidx.core)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.maplibre)
    implementation(libs.work.runtime)
}

tasks.withType<ScalaCompile>().configureEach {
    // https://github.com/gradle/gradle/issues/36466
    scalaCompileOptions.additionalParameters?.addAll(listOf("-deprecation", "-feature", "-Xunchecked-java-output-version:17"))
}
