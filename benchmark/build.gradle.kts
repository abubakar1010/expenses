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
