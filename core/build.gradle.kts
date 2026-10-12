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
    testImplementation(libs.munit)
}

tasks.withType<ScalaCompile>().configureEach {
    // -Xunchecked-java-output-version: https://github.com/gradle/gradle/issues/36466
    scalaCompileOptions.additionalParameters = listOf("-deprecation", "-feature", "-Xunchecked-java-output-version:17")
}

tasks.test {
    useJUnit()
}
