package dev.hfps.hoard.app

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Cleartext HTTP stays limited to the loopback address (ADR 0021 "Plain HTTP"): a plain-HTTP hoard-server is reached
 * through the relay on 127.0.0.1 (`LanRelay`), never by opening cleartext app-wide.
 */
class NetworkSecurityConfigTest {
    @Test
    fun cleartextIsAllowedOnlyToLoopback() {
        val file = listOf(File("src/main/res/xml/network_security_config.xml"), File("native/src/main/res/xml/network_security_config.xml")).first { it.isFile }
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val base = doc.getElementsByTagName("base-config")
        assertThat(base.length).isEqualTo(1)
        assertThat(base.item(0).attributes.getNamedItem("cleartextTrafficPermitted").nodeValue).isEqualTo("false")
        val configs = doc.getElementsByTagName("domain-config")
        val domains = ArrayList<String>()
        for (i in 0 until configs.length) {
            val config = configs.item(i)
            if (config.attributes.getNamedItem("cleartextTrafficPermitted")?.nodeValue != "true") continue
            val children = config.childNodes
            for (j in 0 until children.length) {
                val d = children.item(j)
                if (d.nodeName != "domain") continue
                assertThat(d.attributes.getNamedItem("includeSubdomains")?.nodeValue).isEqualTo("false")
                domains += d.textContent.trim()
            }
        }
        assertThat(domains).containsExactly("127.0.0.1", "localhost")
    }
}
