plugins {
    java
    id("net.ltgt.errorprone") version "5.1.0"
}

repositories { mavenCentral() }

// The same checks whatever JDK compiles it: some Error Prone checks only apply from a language level on.
tasks.withType<JavaCompile>().configureEach { options.release = 21 }

// A source set of its own, so there is a JavaCompile that only "every source set" reaches. Not
// `test`: without real tests in it, Gradle 9 fails the test task.
sourceSets { create("extra") }

dependencies {
    errorprone("com.google.errorprone:error_prone_core:2.50.0")
    implementation("sample:lib")
}
