// AGP 9 has built-in Kotlin support: it registers the `kotlin` extension itself,
// so applying org.jetbrains.kotlin.android alongside it fails with
// "Cannot add extension with name 'kotlin'". The Compose and serialization
// compiler plugins are separate from the base Kotlin plugin and still apply.
// Every plugin version is resolved once here. The benchmark module's
// `com.android.test` in particular must be declared at the root: the
// baselineprofile plugin already puts AGP on the build classpath, so requesting
// a version in a subproject fails with "already on the classpath with an
// unknown version".
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.test) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.baselineprofile) apply false
}

/**
 * NFR-MAIN-04's second half — "an automated benchmark suite **run on each
 * release candidate**".
 *
 * Two tasks rather than one, because the two halves of a release check need
 * different hardware and pretending otherwise is how a green build comes to
 * mean less than it looks like.
 *
 * [releaseCandidateCheck] is everything that is meaningful on any device: the
 * architecture rules, the JVM suite, lint, the instrumented suite and the
 * coverage gate. All of it runs on an emulator, and the instrumented suite has
 * been verified green on both an API 35 emulator and an API 36 phone.
 *
 * [performanceCheck] is the NFR-PERF half, and it deliberately does **not**
 * hang off the first. `DashboardBenchmark`'s own header is blunt about why: "an
 * x86_64 emulator on a desktop will beat it by an order of magnitude while
 * saying nothing at all about the phone this app is for". Wiring it into a CI
 * job on a hosted runner would produce numbers that pass and mean nothing,
 * which is worse than not gating at all. It needs a real ARM device with the
 * corpus seeded — see the README.
 */
tasks.register("releaseCandidateCheck") {
    group = "verification"
    description = "Everything a release candidate must pass that does not need real hardware."
    dependsOn(
        ":app:architectureCheck",
        ":app:testDebugUnitTest",
        ":app:lintRelease",
        ":app:connectedDebugAndroidTest",
        ":app:coverageVerify",
        ":app:assembleRelease",
    )
}

tasks.register("performanceCheck") {
    group = "verification"
    description = "Runs the benchmarks and asserts NFR-PERF budgets. Needs a real ARM device."
    dependsOn(":benchmark:connectedBenchmarkReleaseAndroidTest", ":benchmark:verifyPerformance")
}
