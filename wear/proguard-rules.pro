# Vivi Music — Wear OS
#
# The watch build is minified because APK size matters far more here than on a
# phone: watches have little storage and every install goes over Bluetooth.

# ── kotlinx.serialization ────────────────────────────────────────────────────
# Serializers are generated as companions and looked up reflectively; R8 cannot
# see those links and will otherwise strip them, which fails at runtime with a
# SerializationException rather than at build time.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.music.vivi.wearsync.**$$serializer { *; }
-keepclassmembers class com.music.vivi.wearsync.** {
    *** Companion;
    *** INSTANCE;
}
-keepclasseswithmembers class com.music.vivi.wearsync.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# innertube's API models are all @Serializable and decoded from JSON by name.
-keep,includedescriptorclasses class com.music.innertube.**$$serializer { *; }
-keepclassmembers class com.music.innertube.models.** {
    *** Companion;
    <fields>;
}
-keepclasseswithmembers class com.music.innertube.models.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep class com.music.innertube.models.** { *; }

# ── Ktor / OkHttp ────────────────────────────────────────────────────────────
-dontwarn org.slf4j.**
-dontwarn io.ktor.**
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-keepclassmembers class io.ktor.** { volatile <fields>; }

# ── media3 ───────────────────────────────────────────────────────────────────
-dontwarn androidx.media3.**
-keep class androidx.media3.exoplayer.** { *; }

# ── Rhino / NewPipe, pulled in transitively by :innertube ────────────────────
# The watch resolver never calls these, but they must not break the build.
-dontwarn org.mozilla.javascript.**
-dontwarn org.schabi.newpipe.**
-dontwarn javax.annotation.**
-dontwarn java.beans.**

# Coroutines
-keepclassmembers class kotlinx.coroutines.** { volatile <fields>; }
-dontwarn kotlinx.coroutines.**
