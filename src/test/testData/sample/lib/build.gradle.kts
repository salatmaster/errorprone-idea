plugins {
    `java-library`
    id("net.ltgt.errorprone") version "5.1.0"
}

group = "sample"

repositories { mavenCentral() }

// The same checks whatever JDK compiles it: some Error Prone checks only apply from a language level on.
tasks.withType<JavaCompile>().configureEach { options.release = 21 }

dependencies {
    errorprone("com.google.errorprone:error_prone_core:2.50.0")
}
