# Pinned R8 rules for reproducible & auditable builds
-dontobfuscate                          # Disable symbol renaming (essential for auditability)
-keepattributes SourceFile,LineNumberTable
-keepattributes *Annotation*
-keepattributes Signature
-optimizationpasses 5

# Ensure core framework APIs and entries are preserved
-keep class com.trove.app.AppImpl { *; }
-keep class com.trove.activities.** { *; }
-keep class com.trove.services.** { *; }
-keep class com.trove.providers.** { *; }
-keep class com.trove.widgets.** { *; }

# LGPL 2.1 Compliance: Preserve library boundaries for external relinking
-keep class eu.agno3.jcifs.** { *; }
-keep class org.jaudiotagger.** { *; }
