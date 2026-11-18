# Proguard rules for :app

# WorkManager + androidx.startup: WorkManagerInitializer runs via
# androidx.startup reflection, and Room looks up WorkDatabase_Impl by
# canonical name at runtime. Without these the release build crashes on
# launch ("Failed to create an instance of class ...WorkDatabase.canonicalName").
-keep class androidx.startup.** { *; }
-keep class androidx.work.** { *; }
-keep class * extends androidx.room.RoomDatabase { *; }
-keep class * extends androidx.work.ListenableWorker { *; }
-keep class com.trove.app.worker.** { *; }

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
