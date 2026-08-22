import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.baselineprofile)
    jacoco
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
        /**
         * The variant NFR-PERF is measured on.
         *
         * Macrobenchmark needs a build that is **not debuggable** — a debuggable
         * one disables optimisations wholesale, so a number taken there says
         * nothing about what ships. It also needs the five-year seeder, which
         * lives in `src/debug` precisely so it is absent from `release`.
         * Neither shipped variant is both, which is why this one exists.
         *
         * The Baseline Profile plugin's own `benchmarkRelease` would have done,
         * but a source set created for it by `maybeCreate` contributes its
         * sources and *not* its manifest — the plugin has already fixed the
         * manifest location by the time this block runs, so the APK ends up
         * containing a receiver it never declares. A build type declared here
         * has no such problem, and `:benchmark` gets a matching one so the
         * measurement can actually be driven against it.
         */
        create("benchmark") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
            isDebuggable = false
            // Its own id, so the gradle task that installs `benchmarkRelease`
            // cannot replace the seeded build out from under a measurement.
            //
            // `.bench` and not `.benchmark`: the `:benchmark` module's own
            // namespace is `com.app.finance.benchmark`, so that suffix collides
            // with the measuring harness's APK and the install fails with
            // INSTALL_FAILED_VERSION_DOWNGRADE — the two packages being, as far
            // as the platform is concerned, the same one.
            applicationIdSuffix = ".bench"
            versionNameSuffix = "-bench"
        }
        debug {
            // Produces the JaCoCo exec data `coverageReport` reads. Both
            // halves: the JVM suite covers the calculation layer and the
            // instrumented one covers the repositories, and NFR-MAIN-02 names
            // both.
            enableUnitTestCoverage = true
            enableAndroidTestCoverage = true

            // So a debug build and a release build can sit on the same device
            // without one uninstalling the other — which matters when the exit
            // criterion is "the author uses it daily for a week".
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    /**
     * The seeder, compiled into the variant NFR-PERF is measured on.
     *
     * `release` deliberately has no seeder and `debug` is not measurable, so
     * this is where the two requirements meet. After `buildTypes`, because the
     * source set does not exist until the build type that names it does.
     */
    sourceSets {
        // `maybeCreate`, not `getByName`: the Baseline Profile plugin registers
        // its build types after this block runs, so the source set does not
        // exist yet. Creating it here by the name the plugin will use means AGP
        // finds it already configured when it does.
        getByName("benchmark") {
            // `kotlin` as well as `java`: the sources are `.kt`, and AGP 9's
            // built-in compiler reads the Kotlin source set rather than
            // inferring it from the Java one. Contributing only to `java`
            // merges the manifest and compiles nothing, which produces an APK
            // that declares a receiver it does not contain.
            java.srcDir("src/debug/java")
            kotlin.srcDir("src/debug/java")
            // Named explicitly rather than left to convention.
            manifest.srcFile("src/benchmark/AndroidManifest.xml")
            // The Baseline Profile plugin wires its output into the variants it
            // manages; this one it does not know about, so it reads the same
            // generated profile explicitly. Without it the measurement runs
            // against library rules only — which is exactly the state §20.6
            // found NFR-PERF-04 in, and the thing being measured.
            baselineProfiles.srcDir("src/main/generated/baselineProfiles")
            // The manifest is *not* borrowed: `src/benchmark/AndroidManifest.xml`
            // declares the same receiver exported, because a non-debuggable
            // package cannot be reached by `am broadcast` otherwise. The reason
            // is written out there.
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
/**
 * NFR-MAIN-02 — "line coverage >= 80% on the calculation and repository layers".
 *
 * The project had no coverage tool at all until this was written.
 *
 * **JaCoCo rather than Kover, and not by preference.** Kover is the Kotlin-aware
 * choice and would count inline functions and `when` branches more honestly —
 * but 0.9.1 does not recognise AGP 9's variants: it applies its JVM behaviour,
 * finds no `test` task, and reports "no sources" while `koverVerify` passes.
 * A gate that measures nothing and says yes is worse than no gate, so this is
 * wired by hand instead, where every input is visible.
 *
 * **It measures both layers the requirement names**, which it did not until
 * §20.5. `domain/` and `core/` come from the JVM suite — the calculation layer,
 * and the whole of what NFR-MAIN-01 made pure so it could be measured this way.
 * `data/repo/` comes from the instrumented suite, whose execution data exists
 * only now that the suite runs; counting the repositories before that would
 * have reported zero for code that is in fact well covered, which is why the
 * first version of this task measured half the requirement and said so.
 *
 *     ./gradlew :app:testDebugUnitTest :app:connectedDebugAndroidTest
 *     ./gradlew :app:coverageReport   # HTML and XML
 *     ./gradlew :app:coverageVerify   # the 80% gate
 */
val coverageReport = tasks.register<JacocoReport>("coverageReport") {
    group = "verification"
    description = "Line coverage over domain/, core/ and data/repo/ (NFR-MAIN-02)."
    dependsOn("testDebugUnitTest")
    // `mustRunAfter`, not `dependsOn`: this task reads the connected suite's
    // execution data when it is there, and Gradle rightly refuses to guess the
    // order otherwise. Depending on it would be wrong — it would make a
    // coverage report demand a device, when the point of joining the `.ec`
    // files as a tree is that the JVM half still works without one.
    mustRunAfter("connectedDebugAndroidTest")

    executionData.setFrom(
        files(
            layout.buildDirectory.file(
                "outputs/unit_test_code_coverage/debugUnitTest/testDebugUnitTest.exec",
            ),
            // Every connected device writes its own `.ec` under a directory
            // named after it, so this is a tree rather than a path. A tree over
            // a directory that is not there is empty, so a developer who has
            // not run the instrumented suite still gets a JVM-only report --
            // and the gate then fails honestly on the repositories rather than
            // passing on a file that was never written.
            fileTree(layout.buildDirectory.dir("outputs/code_coverage/debugAndroidTest/connected")) {
                include("**/*.ec")
            },
        ),
    )
    // Kotlin's own output, not AGP's merged one: the merged directory carries
    // Room's generated `_Impl` DAOs and serialization's `$$serializer` classes,
    // neither of which anybody wrote or can meaningfully test.
    //
    // Two roots because AGP 9 moved it. `built_in_kotlinc` is where its
    // bundled compiler writes (the same change the root build file's comment
    // is about); `tmp/kotlin-classes` is where the standalone Kotlin plugin
    // used to. A fileTree over a directory that is not there is simply empty,
    // so naming both costs nothing and survives the move back.
    val classFilter: ConfigurableFileTree.() -> Unit = {
        include(
            "com/app/finance/domain/**",
            "com/app/finance/core/**",
            // NFR-MAIN-02 names the repository layer too. It only became
            // measurable once the instrumented suite ran (§20.5).
            "com/app/finance/data/repo/**",
        )
        exclude("**/*_Impl*.class", "**/*\$\$serializer.class")
    }
    classDirectories.setFrom(
        fileTree(
            layout.buildDirectory.dir("intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes"),
            classFilter,
        ),
        fileTree(layout.buildDirectory.dir("tmp/kotlin-classes/debug"), classFilter),
    )
    sourceDirectories.setFrom(files("src/main/java"))

    reports {
        html.required.set(true)
        xml.required.set(true)
    }
}

tasks.register<JacocoCoverageVerification>("coverageVerify") {
    group = "verification"
    description = "Fails below NFR-MAIN-02's 80% on the calculation and repository layers."
    dependsOn(coverageReport)

    executionData.setFrom(coverageReport.get().executionData)
    classDirectories.setFrom(coverageReport.get().classDirectories)
    sourceDirectories.setFrom(coverageReport.get().sourceDirectories)

    violationRules {
        rule {
            limit {
                counter = "LINE"
                value = "COVEREDRATIO"
                minimum = "0.80".toBigDecimal()
            }
        }
    }
}

tasks.register("architectureCheck") {
    group = "verification"
    description = "Fails on android.* imports under domain/ or core/, and on Double/Float money."

    val srcRoot = layout.projectDirectory.dir("src/main/java/com/app/finance")
    val pureSources = fileTree(srcRoot) { include("domain/**/*.kt", "core/**/*.kt") }
    // The money rule reaches src/debug too: the five-year seeder handles
    // paisa, and a guard with a blind spot over one source set is a guard
    // that stops being one the moment code moves into it.
    val allSources = fileTree(layout.projectDirectory) {
        include("src/main/java/com/app/finance/**/*.kt")
        include("src/debug/java/com/app/finance/**/*.kt")
    }

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
                    violations += "${file.name}:${index + 1}  " +
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
    implementation(libs.androidx.biometric)
    // Pinned rather than inherited from biometric 1.1.0 — see libs.versions.toml.
    implementation(libs.androidx.fragment)
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
