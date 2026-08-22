plugins {
    alias(libs.plugins.android.test)
    alias(libs.plugins.baselineprofile)
}

/**
 * The Macrobenchmark module.
 *
 * 04-system-architecture.md §2.2 makes Baseline Profiles mandatory rather than
 * optional: "On low-end hardware these routinely cut Compose cold start by
 * 20–30% by avoiding interpretation of hot composition paths." Compose's
 * historical weakness is exactly this app's target, so the profile is one of
 * the three mitigations the architecture depends on.
 *
 * This is also the module that decides the fallback in §2.2 — if cold start on
 * the reference device misses 800 ms after Baseline Profiles, single-Activity
 * and R8 full mode, the documented response is to move the two hottest screens
 * (entry and ledger) to XML views. That decision belongs here, with numbers.
 *
 * §3.1 says "a single Gradle module, not multi-module". That governs
 * application source; a Macrobenchmark module has to be a separate
 * `com.android.test` project because it instruments the app from outside its
 * own process — there is no way to express it in `:app`.
 */
android {
    namespace = "com.app.finance.benchmark"
    compileSdk = 37

    defaultConfig {
        // The app ships to API 26; Macrobenchmark itself requires 28+. This
        // floor applies to the measuring harness, not to the product.
        minSdk = 28
        targetSdk = 37
        // The standard runner, not `androidx.benchmark.junit4.AndroidBenchmarkRunner`
        // — that one ships with the *micro*benchmark artifact and is absent
        // here, so using it fails with ClassNotFoundException before a single
        // test runs. Macrobenchmark drives the app from a separate process and
        // needs nothing special of the runner.
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    targetProjectPath = ":app"
    experimentalProperties["android.experimental.self-instrumenting"] = true
}

baselineProfile {
    // Generate against whatever device is attached. Note that profile
    // generation needs root, so an AOSP or `google_apis` image is required —
    // `google_apis_playstore` images cannot be rooted and will fail here.
    useConnectedDevices = true
}

dependencies {
    implementation(libs.androidx.benchmark.macro.junit4)
    implementation(libs.junit)
    implementation(libs.androidx.test.ext.junit)
    implementation(libs.androidx.test.runner)
}

/**
 * NFR-MAIN-04 — "performance targets are asserted by an automated benchmark
 * suite run on each release candidate".
 *
 * Macrobenchmark **reports**; it does not assert. `measureRepeated` writes a
 * JSON file and returns, so a target can be missed by 90% and the build stays
 * green — which is not a hypothetical failure mode here: NFR-PERF-04 was
 * recorded as satisfied across two milestones on the strength of a structural
 * test, and nobody read the wall clock until §20.6. This task is the half of
 * NFR-MAIN-04 that was missing.
 *
 * It reads the benchmark output and fails on three things:
 *
 *  1. a metric over its budget in `performance-budget.txt`;
 *  2. an **exempted** metric that has got worse than its recorded ceiling;
 *  3. a budgeted benchmark **absent from the results** — because a suite that
 *     silently skipped everything would otherwise pass, and macrobenchmark does
 *     skip (`BaselineProfileGenerator` needs root and is skipped routinely).
 *
 * The third is the one that makes the other two trustworthy.
 *
 *     ./gradlew :benchmark:connectedBenchmarkReleaseAndroidTest \
 *       -Pandroid.testInstrumentationRunnerArguments.targetPackage=com.app.finance.bench
 *     ./gradlew :benchmark:verifyPerformance
 */
tasks.register("verifyPerformance") {
    group = "verification"
    description = "Fails when a measured NFR-PERF figure exceeds its budget (NFR-MAIN-04)."

    val budgetFile = layout.projectDirectory.file("performance-budget.txt")
    val resultsDir = layout.buildDirectory.dir("outputs/connected_android_test_additional_output")
    inputs.file(budgetFile)
    inputs.dir(resultsDir).optional()

    doLast {
        val budgets = LinkedHashMap<String, Double>()
        val exemptions = LinkedHashMap<String, Double>()
        budgetFile.asFile.readLines().forEach { raw ->
            val line = raw.substringBefore('#').trim()
            if (line.isEmpty()) return@forEach
            when {
                line.startsWith("exempt ") -> {
                    val body = line.removePrefix("exempt ")
                    val key = body.substringBefore(" ceiling ").trim()
                    exemptions[key] = body.substringAfter(" ceiling ").trim().toDouble()
                }
                line.contains("<=") -> {
                    val (key, value) = line.split("<=", limit = 2)
                    budgets[key.trim()] = value.trim().toDouble()
                }
            }
        }

        val files = resultsDir.get().asFile.walkTopDown()
            .filter { it.isFile && it.name.endsWith("benchmarkData.json") }
            .toList()
        check(files.isNotEmpty()) {
            "No benchmark results under ${resultsDir.get().asFile}. " +
                "Run :benchmark:connectedBenchmarkReleaseAndroidTest first — this task " +
                "must not pass by finding nothing."
        }

        val slurper = groovy.json.JsonSlurper()
        val measured = LinkedHashMap<String, Double>()
        files.forEach { file ->
            @Suppress("UNCHECKED_CAST")
            val root = slurper.parse(file) as Map<String, Any>
            @Suppress("UNCHECKED_CAST")
            val runs = root["benchmarks"] as List<Map<String, Any>>
            runs.forEach { run ->
                val name = run["name"] as String
                @Suppress("UNCHECKED_CAST")
                val metrics = run["metrics"] as Map<String, Map<String, Any>>
                metrics.forEach { (metric, stats) ->
                    val median = (stats["median"] as Number).toDouble()
                    measured["$name.$metric"] = median
                }
                // NFR-PERF-06 is the one figure that is derived rather than read
                // off: the benchmark performs six switches and records the frames
                // they cost, so a switch is (frames / 6) at the p95 frame time.
                // Labelled `derivedMsPerSwitch` so the budget file cannot pretend
                // it is a stopwatch reading.
                if (name == "dashboardPeriodSwitch") {
                    val frames = (metrics["frameCount"]?.get("median") as? Number)?.toDouble()
                    val p95 = (metrics["gfxFrameTime95thPercentileMs"]?.get("median") as? Number)?.toDouble()
                    if (frames != null && p95 != null) {
                        measured["$name.derivedMsPerSwitch"] = (frames / SWITCHES_PER_ITERATION) * p95
                    }
                }
            }
        }

        val failures = mutableListOf<String>()
        val exempted = mutableListOf<String>()

        budgets.forEach { (key, budget) ->
            val value = measured[key]
            if (value == null) {
                failures += "$key was never measured — the benchmark did not run or was skipped"
                return@forEach
            }
            val ceiling = exemptions[key]
            when {
                ceiling != null && value > ceiling ->
                    failures += "$key = %.1f, worse than its recorded exemption ceiling of %.1f".format(value, ceiling)
                ceiling != null ->
                    exempted += "$key = %.1f (budget %.1f, exempt below %.1f)".format(value, budget, ceiling)
                value > budget ->
                    failures += "$key = %.1f, over its budget of %.1f".format(value, budget)
                else ->
                    logger.lifecycle("  ok      $key = %.1f (budget %.1f)".format(value, budget))
            }
        }

        exempted.forEach { logger.lifecycle("  EXEMPT  $it") }

        if (failures.isNotEmpty()) {
            throw GradleException(
                buildString {
                    appendLine("Performance budgets violated (${failures.size}):")
                    failures.forEach { appendLine("  $it") }
                    appendLine()
                    appendLine("Budgets and exemptions: benchmark/performance-budget.txt")
                    appendLine("Raise nothing here without recording why — see 06-implementation-log.md §20.14.")
                },
            )
        }
    }
}

/** `DashboardBenchmark.SWITCHES` is 3, performed in both directions. */
val SWITCHES_PER_ITERATION = 6
