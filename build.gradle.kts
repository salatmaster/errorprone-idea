import org.jetbrains.changelog.Changelog
import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("org.jetbrains.intellij.platform")
    alias(libs.plugins.kotlin)
    alias(libs.plugins.changelog)
}

group = providers.gradleProperty("pluginGroup").get()

// The release workflow passes the version it is cutting, so the number in the artifact,
// the tag and the changelog cannot drift apart. Local builds fall back to gradle.properties.
val pluginVersion = providers.environmentVariable("PLUGIN_VERSION")
    .orElse(providers.gradleProperty("pluginVersion"))

version = pluginVersion.get()

kotlin {
    jvmToolchain(21)
    compilerOptions {
        // Without it, a class implementing a platform interface gets a bridge to every default method
        // it does not override — including GradleExecutionHelperExtension.prepareForExecution, which
        // is deprecated and will be removed, leaving a call to a method that no longer exists.
        jvmDefault = org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode.NO_COMPATIBILITY
    }
}

testing {
    suites {
        named<JvmTestSuite>("test") {
            // Declared here rather than as tasks.test { useJUnitPlatform() } so that the engine and
            // the launcher are resolved as a matched set; a hand-picked junit-jupiter leaves Gradle to
            // supply its own launcher, and discovery then dies with "OutputDirectoryCreator not
            // available".
            useJUnitJupiter(libs.versions.junitJupiter)
        }
    }
}

dependencies {
    testImplementation(libs.assertj)

    // BasePlatformTestCase is a JUnit 3 test (junit.framework.TestCase), and only the vintage
    // engine runs those. Declared rather than inherited from the platform test framework, so a
    // change in its dependency graph fails the build instead of silently dropping the tests.
    testImplementation(libs.junit)
    testRuntimeOnly(libs.junit.vintage.engine)

    // The Tooling API client the end-to-end test drives needs slf4j-api, which the IDE's
    // distribution does not ship on this classpath. Without a binding it logs nothing, which is
    // what the test wants.
    testRuntimeOnly(libs.slf4j.api)

    intellijPlatform {
        create(IntelliJPlatformType.IntellijIdeaCommunity, providers.gradleProperty("platformVersion")) {
            useInstaller = false
        }
        bundledPlugin("com.intellij.java")
        bundledPlugin("com.intellij.gradle")
        // Apply Patch, which shows Error Prone's fixes: a platform module, not on the classpath by default.
        bundledModule("intellij.platform.vcs.impl")
        pluginVerifier()
        zipSigner()
        testFramework(TestFrameworkType.Platform)
    }
}

tasks.test {
    // The end-to-end test runs real builds with the Gradle that runs this one, so it downloads no
    // distribution. -PtestGradleVersion=8.9 runs it against another Gradle, to find the minimum.
    systemProperty("errorprone.test.gradleHome", gradle.gradleHomeDir!!.absolutePath)
    providers.gradleProperty("testGradleVersion").orNull?.let {
        systemProperty("errorprone.test.gradleVersion", it)
    }
    // Tests run on the IDE's own runtime, which an older Gradle may refuse to run on:
    // -PtestJavaHome=/path/to/jdk21 gives the nested builds another JVM.
    providers.gradleProperty("testJavaHome").orNull?.let {
        systemProperty("errorprone.test.javaHome", it)
    }
}

// Developer convenience: ./gradlew runIde -PsampleProject=src/test/testData/sample opens that
// project directly, so the plugin can be exercised without clicking through the welcome screen.
fun org.jetbrains.intellij.platform.gradle.tasks.RunIdeTask.openSampleProject() {
    providers.gradleProperty("sampleProject").orNull?.let { path ->
        args(file(path).absolutePath)
        systemProperty("idea.trust.all.projects", "true")
        // A fresh sandbox otherwise blocks on the end-user agreement dialog before any plugin
        // code runs, which makes the sandbox useless for exercising the plugin.
        systemProperty("jb.consents.confirmation.enabled", "false")
        systemProperty("jb.privacy.policy.text", "<!--999.999-->")
        systemProperty("idea.initially.ask.config", "never")
        systemProperty("idea.log.debug.categories", "io.github.salatmaster.errorprone")
    }
}

tasks.runIde { openSampleProject() }

// ./gradlew runIdeOn -PideVersion=263.6259.32 runs the sandbox on another IDE build, an EAP say: what
// moves between platform versions shows only in the running IDE, the Plugin Verifier does not model how
// the platform wires a plugin's class loader.
providers.gradleProperty("ideVersion").orNull?.let { ideVersion ->
    intellijPlatformTesting.runIde.register("runIdeOn") {
        type = IntelliJPlatformType.IntellijIdea
        version = ideVersion
        useInstaller = true
        task { openSampleProject() }
    }
}

// Read-only use of the changelog: the file is cut by .github/scripts/cut-changelog.sh before the
// release builds, so patchChangelog is deliberately never run and cannot disagree with it.
changelog {
    version = pluginVersion
}

intellijPlatform {
    pluginConfiguration {
        version = pluginVersion

        // <change-notes> is what the Marketplace shows as "What's new". The section of the version
        // being released is used rather than [Unreleased], because the release workflow cuts the
        // file before building and by then the entry has already moved.
        changeNotes = pluginVersion.map { version ->
            with(changelog) {
                val item = getOrNull(version) ?: getUnreleased()
                renderItem(item.withHeader(false).withEmptySections(false), Changelog.OutputType.HTML)
            }
        }

        ideaVersion {
            sinceBuild = providers.gradleProperty("pluginSinceBuild")
            // No upper bound: the plugin must not stop loading when a new IDE ships.
            untilBuild.unset()
        }
    }
    pluginVerification {
        ides {
            create(IntelliJPlatformType.IntellijIdeaCommunity, providers.gradleProperty("platformVersion"))
            create(IntelliJPlatformType.IntellijIdea, providers.gradleProperty("platformVersion"))
        }
    }

    // Credentials come from the environment so nothing sensitive lives in the repository.
    signing {
        certificateChain = providers.environmentVariable("CERTIFICATE_CHAIN")
        privateKey = providers.environmentVariable("PRIVATE_KEY")
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
    }

    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
    }
}
