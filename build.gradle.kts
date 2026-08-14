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
