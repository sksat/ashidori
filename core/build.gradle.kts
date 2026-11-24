plugins {
    scala
    `java-library`
}

java {
    toolchain { languageVersion = JavaLanguageVersion.of(17) }
}

dependencies {
    api(libs.scala3.library)
    api(libs.ujson)
    // QR codes of registration links (PairQr). Apache License 2.0.
    implementation(libs.zxing.core)
    // HPKE seal of the browser grant (ViewerPairing). Apache License 2.0.
    implementation(libs.tink.android)
    // The map library's HTTP client is given MapHttp's interceptors (the OkHttp MapLibre brings). Apache-2.0.
    api(libs.okhttp)
    testImplementation(libs.munit)
}

tasks.withType<ScalaCompile>().configureEach {
    // -Xunchecked-java-output-version: https://github.com/gradle/gradle/issues/36466
    scalaCompileOptions.additionalParameters = listOf("-deprecation", "-feature", "-Xunchecked-java-output-version:17")
}

tasks.test {
    useJUnit()
}

// Fixtures of the web viewer's tests (server/web/test/fixtures), written by
// the app's own Parquet writer, crypto and timeline code (WebFixtures.scala).
tasks.register<JavaExec>("webFixtures") {
    group = "verification"
    description = "Writes the web viewer's test fixtures with the app's own code."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("ashidori.core.WebFixtures")
    args(rootProject.file("server/web/test/fixtures").absolutePath)
    finalizedBy("viewerPairingFixtures")
}

// CPU cost of archiving detail-log lines, stage by stage (bench/CompactionBench.scala).
tasks.register<JavaExec>("compactionBench") {
    group = "verification"
    description = "Times the archiving pipeline on synthetic detail-log lines."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("ashidori.core.bench.CompactionBench")
    maxHeapSize = "2g"
    args((project.findProperty("args") as String? ?: "").split(" ").filter { it.isNotEmpty() })
}

// Interop vectors of browser pairing v1 (server/web/test/fixtures/viewer-pairing.json),
// with grants sealed by Tink (ViewerPairingFixtures.scala).
tasks.register<JavaExec>("viewerPairingFixtures") {
    group = "verification"
    description = "Writes the web viewer's browser-pairing vectors with the app's own code."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("ashidori.core.ViewerPairingFixtures")
    args(rootProject.file("server/web/test/fixtures").absolutePath)
}
