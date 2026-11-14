# :core:smb rules, applied by R8 to every app that uses the module (:app, :native). Moved from
# app/proguard-rules.pro unchanged (ADR 0019).
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
