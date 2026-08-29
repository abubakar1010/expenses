# DayBook — release shrinking rules.
# 04-system-architecture.md §10: R8 full mode, resource shrinking, single dex.
# Keep this file small. Every -keep is a shrinking opportunity given up, and the
# APK budget (NFR-SIZE-01, 6 MB) has no slack to donate.

# --- kotlinx.serialization -------------------------------------------------
# The plugin generates a synthetic Companion.serializer() per @Serializable
# class. R8 full mode cannot see the reflective link from the generated
# serializer back to its owner, so both sides are kept explicitly.
-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    static <1>$Companion Companion;
}
-if @kotlinx.serialization.Serializable class ** {
    static **$* *;
}
-keepclassmembers class <2>$<3> {
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclassmembers class **$serializer {
    *** INSTANCE;
}

# --- Room ------------------------------------------------------------------
# Room's generated implementations are referenced by name from the generated
# database class; the annotations themselves are compile-time only.
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-dontwarn androidx.room.paging.**

# --- Diagnostics -----------------------------------------------------------
# 04 §8: no crash-reporting SDK. Unhandled exceptions are written to an
# app-private log file, so line numbers must survive shrinking to be useful.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# The five-year seeder, for the `benchmarkRelease` variant only.
#
# It lives in `src/debug/java` so it is absent from `release`, and is compiled
# into `benchmarkRelease` as well because NFR-PERF's corpus has to exist on the
# variant the targets are defined against. Nothing in `main` calls it and the
# receiver is reached only through the manifest, so R8 full mode would strip
# both and the benchmark would measure an empty database while reporting
# success.
#
# This rule sits in the shared file rather than a variant-specific one because
# the class is simply not present in `release`, where the rule is a no-op.
-keep class com.app.finance.dev.** { *; }
