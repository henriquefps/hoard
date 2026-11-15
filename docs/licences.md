# Licences

What the Hoard APK (`:native`, 2.0.0 and later) contains, as listed in Settings › Licences
(`android/native/src/main/java/dev/hfps/hoard/app/ui/settings/LicencesScreen.kt`). Hoard 1's web stack (Capacitor,
React, CodeMirror, KaTeX…) left the app with 2.0.0. hoard-server and its web gallery (`server/`, `server/web/`) are
separate programs with their own dependencies. A new library in the APK is added here and to the screen in the same
PR; check with `./gradlew :native:dependencies --configuration releaseRuntimeClasspath`.

| Component | Licence | Where |
| --- | --- | --- |
| Hoard | AGPL-3.0-or-later | this repository ([LICENSE](../LICENSE)) |
| LibRaw | LGPL 2.1 or CDDL 1.0 | vendored in `android/core/image/src/main/cpp/third_party/LibRaw/` (JNI) |
| smbj, asn-one | Apache License 2.0 | `com.hierynomus:smbj`, `com.hierynomus:asn-one` |
| Bouncy Castle | MIT | `org.bouncycastle` (smbj's crypto, shrunk by R8) |
| MBassador | MIT | `net.engio:mbassador` (smbj's event bus) |
| SLF4J | MIT | `org.slf4j` |
| AndroidX, Jetpack Compose, Room | Apache License 2.0 | `androidx.*` (Compose, Material 3, Navigation, Lifecycle, Room, WorkManager, AppCompat, ExifInterface…) |
| Kotlin, kotlinx.coroutines, kotlinx.serialization, JetBrains annotations | Apache License 2.0 | `org.jetbrains.kotlin`, `org.jetbrains.kotlinx`, `org.jetbrains:annotations` |
| Guava, javax.inject, JSpecify | Apache License 2.0 | transitive (ML Kit, AndroidX) |
| Java core library desugaring (desugar_jdk_libs) | GPL-2.0 with the Classpath Exception | `com.android.tools:desugar_jdk_libs` (`java.time` on Android 7) |
| ML Kit face detection, with its Google Play services and Firebase components | ML Kit terms of service | `com.google.mlkit:face-detection` (bundled model) |
| Google code scanner (ML Kit, through Google Play services) | ML Kit terms of service | `com.google.android.gms:play-services-code-scanner` (QR codes for pairing; the scanner module is downloaded by Google Play services, no camera permission) |
| Inter | SIL Open Font License 1.1 | `android/core/designsystem/fonts/` |
| Phosphor Icons | MIT | generated as `ImageVector`s (`android/scripts/icons/`) |
| Mermaid | MIT | vendored in `android/native/src/main/assets/mermaid/` (text viewer) |
| Highlights (SnipMe) | Apache License 2.0 | `dev.snipme:highlights` (syntax highlighting) |
