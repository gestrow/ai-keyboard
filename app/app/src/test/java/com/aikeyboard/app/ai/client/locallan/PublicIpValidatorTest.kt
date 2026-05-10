// SPDX-License-Identifier: GPL-3.0-only
package com.aikeyboard.app.ai.client.locallan

import com.aikeyboard.app.ai.client.locallan.util.PublicIpValidator
import com.aikeyboard.app.ai.client.locallan.util.PublicIpValidator.Classification
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PublicIpValidatorTest {

    @Test fun loopback_ipv4_classifiesLoopback() {
        assertEquals(Classification.LOOPBACK, PublicIpValidator.classifyHost("127.0.0.1"))
        assertEquals(Classification.LOOPBACK, PublicIpValidator.classifyHost("127.255.255.254"))
    }

    @Test fun localhost_classifiesLoopback() {
        assertEquals(Classification.LOOPBACK, PublicIpValidator.classifyHost("localhost"))
        assertEquals(Classification.LOOPBACK, PublicIpValidator.classifyHost("LOCALHOST"))
    }

    @Test fun ipv6Loopback_classifiesLoopback() {
        assertEquals(Classification.LOOPBACK, PublicIpValidator.classifyHost("::1"))
        assertEquals(Classification.LOOPBACK, PublicIpValidator.classifyHost("[::1]"))
    }

    @Test fun rfc1918_10_classifiesPrivate() {
        assertEquals(Classification.PRIVATE, PublicIpValidator.classifyHost("10.0.0.1"))
        assertEquals(Classification.PRIVATE, PublicIpValidator.classifyHost("10.255.255.255"))
    }

    @Test fun rfc1918_172_lowEdge_classifiesPrivate() {
        assertEquals(Classification.PRIVATE, PublicIpValidator.classifyHost("172.16.0.1"))
    }

    @Test fun rfc1918_172_highEdge_classifiesPrivate() {
        assertEquals(Classification.PRIVATE, PublicIpValidator.classifyHost("172.31.255.254"))
    }

    @Test fun rfc1918_172_15_isPublic() {
        assertEquals(Classification.PUBLIC, PublicIpValidator.classifyHost("172.15.0.1"))
    }

    @Test fun rfc1918_172_32_isPublic() {
        assertEquals(Classification.PUBLIC, PublicIpValidator.classifyHost("172.32.0.1"))
    }

    @Test fun rfc1918_192_168_classifiesPrivate() {
        assertEquals(Classification.PRIVATE, PublicIpValidator.classifyHost("192.168.1.42"))
    }

    @Test fun linkLocal_classifiesLinkLocal() {
        assertEquals(Classification.LINK_LOCAL, PublicIpValidator.classifyHost("169.254.1.1"))
        assertEquals(Classification.LINK_LOCAL, PublicIpValidator.classifyHost("fe80::1"))
    }

    @Test fun publicIp_classifiesPublic() {
        assertEquals(Classification.PUBLIC, PublicIpValidator.classifyHost("8.8.8.8"))
        assertEquals(Classification.PUBLIC, PublicIpValidator.classifyHost("1.1.1.1"))
    }

    @Test fun hostname_classifiesHostname() {
        assertEquals(Classification.HOSTNAME, PublicIpValidator.classifyHost("ollama.example.com"))
        assertEquals(Classification.HOSTNAME, PublicIpValidator.classifyHost("my-pi.local"))
    }

    @Test fun multicast_classifiesInvalid() {
        assertEquals(Classification.INVALID, PublicIpValidator.classifyHost("224.0.0.1"))
        assertEquals(Classification.INVALID, PublicIpValidator.classifyHost("0.1.2.3"))
    }

    @Test fun emptyHost_classifiesInvalid() {
        assertEquals(Classification.INVALID, PublicIpValidator.classifyUrl(""))
        assertEquals(Classification.INVALID, PublicIpValidator.classifyUrl("not a url"))
    }

    @Test fun classifyUrl_extractsHostFromFullUrl() {
        assertEquals(Classification.PRIVATE, PublicIpValidator.classifyUrl("http://192.168.1.42:11434"))
        assertEquals(Classification.PUBLIC, PublicIpValidator.classifyUrl("http://8.8.8.8:8080/v1/chat/completions"))
        assertEquals(Classification.LOOPBACK, PublicIpValidator.classifyUrl("http://localhost:1234/api"))
    }

    @Test fun classifyUrl_handlesNoScheme() {
        // Without a scheme URI cannot extract a host — INVALID is the conservative result.
        assertEquals(Classification.INVALID, PublicIpValidator.classifyUrl("192.168.1.42:11434"))
    }

    @Test fun isSafeForCleartext_separatesSafeFromUnsafe() {
        assertTrue(PublicIpValidator.isSafeForCleartext(Classification.LOOPBACK))
        assertTrue(PublicIpValidator.isSafeForCleartext(Classification.PRIVATE))
        assertTrue(PublicIpValidator.isSafeForCleartext(Classification.LINK_LOCAL))
        assertTrue(PublicIpValidator.isSafeForCleartext(Classification.HOSTNAME))
        assertFalse(PublicIpValidator.isSafeForCleartext(Classification.PUBLIC))
        assertFalse(PublicIpValidator.isSafeForCleartext(Classification.INVALID))
    }
}
