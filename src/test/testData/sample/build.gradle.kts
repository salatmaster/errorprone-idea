import net.ltgt.gradle.errorprone.errorprone

plugins {
    java
    id("net.ltgt.errorprone") version "5.1.0"
}

repositories { mavenCentral() }

tasks.withType<JavaCompile>().configureEach {
    // The same checks whatever JDK compiles it: some Error Prone checks only apply from a language level on.
    options.release = 21
    // NullAway checks only what is @NullMarked: the delivery package, not the shop.
    options.errorprone.option("NullAway:OnlyNullMarked", "true")
}

// A source set of its own, so there is a JavaCompile that only "every source set" reaches. Not
// `test`: without real tests in it, Gradle 9 fails the test task.
sourceSets { create("extra") }

dependencies {
    errorprone("com.google.errorprone:error_prone_core:2.50.0")
    // An Error Prone plugin: its findings come through javac like any built-in check's.
    errorprone("com.uber.nullaway:nullaway:0.14.2")
    implementation("org.jspecify:jspecify:1.0.1")
    implementation("sample:lib")
}
