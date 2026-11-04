# ====================================================================
# Bol — ProGuard / R8 rules
# ====================================================================
# These rules keep classes that are used via reflection, JNI, or that
# the framework instantiates by name. R8's bundled rules already cover
# Compose, Kotlin, AndroidX, and Room. Everything below is for the
# reflection-heavy or native-binding deps we ship that R8 can't infer.
# ====================================================================

# Keep line numbers in stack traces — invaluable for diagnosing
# production crashes once they show up in Play Console / Sentry. The
# source-file rename below disconnects line numbers from the original
# .kt file paths so we don't leak our package layout.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ====================================================================
# kotlinx.serialization
# --------------------------------------------------------------------
# We use @Serializable on navigation routes (BolNavGraph) and a few
# data shapes that go through Json.encodeToString. R8 strips the
# generated $$serializer companions if we don't keep them — runtime
# crashes with NoSuchMethodError otherwise.
# ====================================================================
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

# Keep the runtime serializer registries in the json package.
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Keep generated serializers + Companion objects for our own classes.
-keep,includedescriptorclasses class com.bol.app.**$$serializer { *; }
-keepclassmembers class com.bol.app.** {
    *** Companion;
}
-keepclasseswithmembers class com.bol.app.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Navigation Compose Serializable routes: Kotlin compiler generates
# `Companion` + serializer for `@Serializable data object/class`
# routes. The block above already catches them, but explicit for
# clarity since this is what powers BolNavGraph type-safe routing.
-keep @kotlinx.serialization.Serializable class com.bol.app.ui.navigation.** { *; }

# ====================================================================
# Room
# --------------------------------------------------------------------
# Room ships its own R8 rules in androidx.room:room-runtime, but we
# add belt-and-braces keeps for entity classes since they're accessed
# reflectively when migrations run.
# ====================================================================
-keep class * extends androidx.room.RoomDatabase { *; }
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao class * { *; }
-keepclassmembers class * {
    @androidx.room.* <methods>;
    @androidx.room.* <fields>;
}

# Keep our entity package fully — Room migrations + DAOs reference
# field names by string, and R8 rename would break that.
-keep class com.bol.app.data.local.entity.** { *; }

# ====================================================================
# Koin
# --------------------------------------------------------------------
# Modern Koin (3.x+) uses lambdas for definitions and minimal
# reflection. The class names ARE used in error messages so we keep
# our own di package readable; otherwise crashes would be hard to
# diagnose.
# ====================================================================
-keep class com.bol.app.di.** { *; }

# ====================================================================
# LiteRT-LM + LiteRT (Google AI Edge — on-device task model and speech)
# --------------------------------------------------------------------
# JNI bindings between Kotlin and the native runtime. Keep the public
# surface so native lookups by name don't fail.
# ====================================================================
-keep class com.google.ai.edge.litertlm.** { *; }
-keepclassmembers class com.google.ai.edge.litertlm.** { *; }
-keep class com.google.ai.edge.litert.** { *; }

# ONNX Runtime (EmbeddingGemma for search) and the bundled SQLite (search
# index): JNI bindings, same reason as above.
-keep class ai.onnxruntime.** { *; }
-keep class androidx.sqlite.driver.bundled.** { *; }


# ====================================================================
# AndroidX Glance (home screen widget)
# --------------------------------------------------------------------
# Glance composables get instantiated by the framework via class name
# from the widget XML metadata.
# ====================================================================
-keep class com.bol.app.widget.** { *; }
-keep class * extends androidx.glance.appwidget.GlanceAppWidget { *; }
-keep class * extends androidx.glance.appwidget.GlanceAppWidgetReceiver { *; }

# ====================================================================
# AndroidX WorkManager workers
# --------------------------------------------------------------------
# Workers are instantiated by name from the framework — never let R8
# remove or rename them.
# ====================================================================
-keep class * extends androidx.work.Worker { *; }
-keep class * extends androidx.work.CoroutineWorker { *; }
-keep class * extends androidx.work.ListenableWorker { *; }

# ====================================================================
# Bol services / receivers / providers
# --------------------------------------------------------------------
# These are referenced by name in AndroidManifest.xml. R8 already
# keeps everything declared in the manifest, but explicit keeps let
# the rule file double as documentation of what's framework-loaded.
# ====================================================================
-keep class com.bol.app.service.BolProcessingService { *; }
-keep class com.bol.app.service.ReminderReceiver { *; }
-keep class com.bol.app.service.ReminderActionReceiver { *; }
-keep class com.bol.app.service.BootCompletedReceiver { *; }
-keep class com.bol.app.ui.share.ShareReceiveActivity { *; }
-keep class com.bol.app.MainActivity { *; }
-keep class com.bol.app.BolApplication { *; }

# ====================================================================
# Kotlin coroutines — Companion stripping is the most common failure.
# ====================================================================
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}
-keepclassmembernames class kotlinx.** {
    volatile <fields>;
}

# ====================================================================
# Suppress framework / lib warnings that don't affect runtime.
# ====================================================================
-dontwarn java.lang.invoke.StringConcatFactory
-dontwarn javax.lang.model.**
-dontwarn javax.annotation.**
