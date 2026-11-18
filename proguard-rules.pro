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

# Suppress R8 missing class warnings for optional desktop/JVM classes in Netty, Ktor, SSHD, Jaudiotagger
-dontwarn java.awt.**
-dontwarn javax.imageio.**
-dontwarn java.lang.management.**
-dontwarn javax.management.**
-dontwarn javax.swing.**
-dontwarn org.apache.log4j.**
-dontwarn org.apache.logging.log4j.**
-dontwarn org.eclipse.jetty.npn.**
-dontwarn reactor.blockhound.**
-dontwarn io.netty.**
-dontwarn org.apache.sshd.**
-dontwarn org.bouncycastle.**
-dontwarn com.hierynomus.**
-dontwarn org.slf4j.**
-dontwarn sun.misc.**
-dontwarn com.gemalto.jp2.**
