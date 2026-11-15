# kotlinx.serialization models of the hoard-server API (ADR 0021).
-keepclassmembers @kotlinx.serialization.Serializable class dev.hfps.hoard.server.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class dev.hfps.hoard.server.**$$serializer { *; }
