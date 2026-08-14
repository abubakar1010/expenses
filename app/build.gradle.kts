import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.baselineprofile)
}

android {
    namespace = "com.app.finance"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.app.finance"
        // NFR-COMP-01: minSdk 26 (Android 8.0), targetSdk latest stable.
        // API 37 is a stable base SDK on this machine (PreviewSdkInt=0), and
        // minSdk 26 is what makes java.time available without desugaring.
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // 04 §10 — restrict language resources to shipped locales; removes a
    // surprising amount of AndroidX translation weight from the APK.
    //
    // "bn" is deliberately absent until `values-bn/` exists. Declaring a locale
    // with no resources ships a build that advertises Bengali and renders
    // English, and financial vocabulary is the wrong place to guess.
    androidResources {
        localeFilters += listOf("en")
    }

    signingConfigs {
        // Release signing comes from a gitignored keystore.properties. Absent
        // one — a fresh clone, or CI without secrets — assembleRelease still
        // succeeds and simply produces an unsigned APK, so the shrinking and
        // size checks stay runnable by anyone.
        val props = rootProject.file("keystore.properties")
        if (props.exists()) {
            create("release") {
                val config = Properties().apply { props.inputStream().use { load(it) } }
                storeFile = rootProject.file(config.getProperty("storeFile"))
                storePassword = config.getProperty("storePassword")
                keyAlias = config.getProperty("keyAlias")
                keyPassword = config.getProperty("keyPassword")
            }
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
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
        debug {
            // So a debug build and a release build can sit on the same device
            // without one uninstalling the other — which matters when the exit
            // criterion is "the author uses it daily for a week".
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    lint {
        // The audit that prompted this pass found ten unused string resources
        // and a dozen hardcoded ones; UnusedResources and HardcodedText would
        // both have caught them. A baseline records what exists today so new
        // issues fail the build without a flag day.
        abortOnError = true
        warningsAsErrors = false
        checkReleaseBuilds = true
        baseline = file("lint-baseline.xml")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

// With AGP 9's built-in Kotlin, jvmTarget is not set here: it defaults to
// android.compileOptions.targetCompatibility (17, above), and stating it twice
// is how the two drift apart.

baselineProfile {
    // One profile for the whole app rather than one per variant.
    mergeIntoMain = true
}

/**
 * The two rules 04-system-architecture.md makes structural rather than
 * advisory. Breaking either fails the build.
 *
 *   §3.1 "Package boundaries plus a lint rule forbidding `android.*` imports
 *         inside `domain/` achieves the same discipline at a fraction of the
 *         cost [of multi-module]."
 *   §4.1 "`Double` for money is prohibited throughout. Not discouraged —
 *         prohibited, and enforced by a lint rule, because a rounding bug in a
 *         ledger is silent and discovered months later."
 *
 * A Gradle verification task rather than a `com.android.lint` module: the
 * guarantee the document asks for is that the build fails, and a lint module
 * would add a whole Gradle project plus an artifact to publish in order to also
 * show squiggles in the IDE. Worth doing once there is a second developer to
 * see them.
 *
 * Everything is declared inside the task so this file has no top-level `val`s.
 * AGP's `lintVital` analyses Gradle scripts, and script-level properties crash
 * it: `SymbolLightClassForScript.getOwnFields` throws
 * "findFirCompiledSymbol only works on compiled declarations".
 */
tasks.register("architectureCheck") {
    group = "verification"
    description = "Fails on android.* imports under domain/ or core/, and on Double/Float money."

    val srcRoot = layout.projectDirectory.dir("src/main/java/com/app/finance")
    val pureSources = fileTree(srcRoot) { include("domain/**/*.kt", "core/**/*.kt") }
    val allSources = fileTree(srcRoot) { include("**/*.kt") }

    inputs.files(pureSources, allSources).withPathSensitivity(PathSensitivity.RELATIVE)
    val marker = layout.buildDirectory.file("reports/architectureCheck.txt")
    outputs.file(marker)

    val rootDir = srcRoot.asFile
    val pureFiles = pureSources.files
    val allFiles = allSources.files

    doLast {
        val violations = mutableListOf<String>()

        // Rule 1 — domain/ and core/ stay pure Kotlin. That is what makes them
        // JVM-testable in milliseconds with no emulator, which is in turn what
        // makes the >=90% coverage target of §9 achievable at all.
        pureFiles.forEach { file ->
            file.readLines().forEachIndexed { index, line ->
                val trimmed = line.trimStart()
                if (trimmed.startsWith("import android.") || trimmed.startsWith("import androidx.")) {
                    violations += "${file.relativeTo(rootDir).path}:${index + 1}  " +
                        "platform import in a pure layer: ${trimmed.removePrefix("import ").trim()}"
                }
            }
        }

        // Rule 2 — money is never floating point. Keyed on money-shaped names so
        // genuine ratios (a bar's `fraction`, a savings rate) are not caught.
        val moneyName = Regex(
            """\b\w*(amount|paisa|minor|money|balance|total|limit|spent|earned)\w*\s*:\s*(Double|Float)\b""",
            RegexOption.IGNORE_CASE,
        )
        allFiles.forEach { file ->
            file.readLines().forEachIndexed { index, line ->
                moneyName.find(line)?.let { match ->
                    violations += "${file.relativeTo(rootDir).path}:${index + 1}  " +
                        "money must be Money/Long paisa, not ${match.groupValues[2]}: ${line.trim()}"
                }
            }
        }

        marker.get().asFile.apply {
            parentFile.mkdirs()
            writeText(if (violations.isEmpty()) "ok\n" else violations.joinToString("\n"))
        }

        if (violations.isNotEmpty()) {
            throw GradleException(
                buildString {
                    appendLine("Architecture rules violated (${violations.size}):")
                    violations.forEach { appendLine("  $it") }
                    appendLine()
                    appendLine("See 04-system-architecture.md §3.1 and §4.1.")
                },
            )
        }
    }
}

tasks.named("preBuild") { dependsOn("architectureCheck") }

ksp {
    // Exported schemas are the input to the migration tests required by 03 §8.
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.generateKotlin", "true")
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)

    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // Installs the Baseline Profile on first run. ~30 KB, and 04 §2.2 makes the
    // profile one of the three mandatory mitigations for Compose cold start.
    implementation(libs.androidx.profileinstaller)
    baselineProfile(project(":benchmark"))

    // 04 §2.2 — no @Preview code reachable from release. Tooling is debug-only;
    // the component gallery is a debug-only route instead of @Preview functions.
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
}
