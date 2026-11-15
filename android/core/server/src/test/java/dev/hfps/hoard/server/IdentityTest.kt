package dev.hfps.hoard.server

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class IdentityTest {
    private fun bytes(size: Int, seed: Long) = ByteArray(size) { i -> (((i.toLong() * 2654435761L + seed * 97L) shr 13) and 0xff).toByte() }

    /** The vectors the server and Reveal check (ADR 0013 "Tests"), edges included (0, 2 MiB, 2 MiB + 1). */
    @Test
    fun fingerprintMatchesTheSharedVectors() {
        val text = javaClass.classLoader!!.getResource("fingerprint-vectors.txt")!!.readText()
        var checked = 0
        for (line in text.lines()) {
            if (line.isBlank() || line.startsWith("#")) continue
            val (size, seed, expected) = line.trim().split(Regex("\\s+"))
            assertThat(Fingerprint.of(bytes(size.toInt(), seed.toLong()))).isEqualTo(expected)
            checked++
        }
        assertThat(checked).isAtLeast(5)
    }

    @Test
    fun fileAndSourceGiveTheSameFingerprint() {
        val data = bytes(3 * 1024 * 1024 + 17, 5)
        val file = java.io.File.createTempFile("fpr", ".bin").apply { writeBytes(data); deleteOnExit() }
        assertThat(Fingerprint.of(file)).isEqualTo(Fingerprint.of(data))
        assertThat(Fingerprint.isValid(Fingerprint.of(data))).isTrue()
        assertThat(Fingerprint.isValid("v1:abc")).isFalse()
    }

    /** ADR 0014 "Item id", checked against hoard-server 0.9.0's answer for the same main file. */
    @Test
    fun derivedItemIdIsTheServers() {
        assertThat(ItemIds.derive("v1:f797144a0a4553336d27ccf6d34759370f69ee589ba3265078bd6fcd9c9a461d")).isEqualTo("03f481c5-314d-899d-a984-a40fc5cdc8cb")
        assertThat(ItemIds.derive("v1:933f17cc1ba9ce658c0b22b5808903959e22c87e3c89d65f3bed4c95bf6d08ec")).isEqualTo("04fb2a84-627f-89e5-85e2-18534059dcd2")
    }

    @Test
    fun parsesPairingCodesInAnyOrder() {
        val code = PairingCode.parse("revealsync://pair?server=00a35036-6161-4806-badc-bad74e5be397&token=HsXq_b-x&url=http%3A%2F%2F10.0.2.2%3A3981&v=1")
        assertThat(code).isEqualTo(PairingCode("http://10.0.2.2:3981", "HsXq_b-x", "00a35036-6161-4806-badc-bad74e5be397"))
        assertThat(PairingCode.parse("  revealsync://pair?v=1&url=https%3A%2F%2Fphotos.example.org%2F&token=t  ")!!.url).isEqualTo("https://photos.example.org")
        assertThat(PairingCode.parse("revealsync://pair?v=2&url=http%3A%2F%2Fa&token=t")).isNull()
        assertThat(PairingCode.parse("revealsync://pair?v=1&url=ftp%3A%2F%2Fa&token=t")).isNull()
        assertThat(PairingCode.parse("revealsync://pair?v=1&url=http%3A%2F%2Fa")).isNull()
        assertThat(PairingCode.parse("https://example.org")).isNull()
    }
}
