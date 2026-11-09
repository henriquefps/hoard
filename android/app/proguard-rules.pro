# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# --- HoardSmb native plugin -------------------------------------------------------------------

# Capacitor finds plugin methods/callbacks by reflection (annotations + method names).
-keep @com.getcapacitor.annotation.CapacitorPlugin public class * {
    @com.getcapacitor.annotation.ActivityCallback <methods>;
    @com.getcapacitor.annotation.PermissionCallback <methods>;
    @com.getcapacitor.PluginMethod public <methods>;
}
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod

# smbj: keep its classes (they are small next to Bouncy Castle); its event bus (mbassador) dispatches
# to @Handler methods by reflection.
-keep class com.hierynomus.** { *; }
-keep class net.engio.mbassy.** { *; }
-keepclassmembers class * {
    @net.engio.mbassy.listener.Handler *;
}
-dontwarn com.hierynomus.**
-dontwarn net.engio.mbassy.**

# smbj's SPNEGO/Kerberos path is skipped on Android; its javax.security.auth.kerberos / GSS types
# are absent there.
-dontwarn javax.security.auth.kerberos.**
-dontwarn org.ietf.jgss.**
-dontwarn javax.naming.**

# Bouncy Castle: smbj uses the lightweight API directly (digests, ciphers, MACs); R8 keeps what is
# referenced. Optional JCA/LDAP bits reference classes Android does not ship.
-dontwarn org.bouncycastle.**

# SLF4J 2 finds its (no-op) provider through ServiceLoader.
-keep class org.slf4j.nop.** { *; }
-keep class * implements org.slf4j.spi.SLF4JServiceProvider { *; }
-dontwarn org.slf4j.**

# Keep line numbers for readable crash reports.
-keepattributes SourceFile,LineNumberTable

# Tink (pulled in by @capacitor-community/sqlite through androidx.security) references compile-only
# annotations.
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**
