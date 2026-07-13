package com.dwinovo.numen.security;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;

import static org.junit.jupiter.api.Assertions.*;

class PublicUrlPolicyTest {
    @Test void rejectsNonHttpAndLocalTargets() {
        assertThrows(IllegalArgumentException.class, () -> PublicUrlPolicy.validate("file:///etc/passwd"));
        assertThrows(IllegalArgumentException.class, () -> PublicUrlPolicy.validate("http://localhost/x"));
        assertThrows(IllegalArgumentException.class, () -> PublicUrlPolicy.validate("http://127.0.0.1/x"));
        assertThrows(IllegalArgumentException.class, () -> PublicUrlPolicy.validate("http://[::1]/x"));
        assertThrows(IllegalArgumentException.class, () -> PublicUrlPolicy.validate("http://169.254.169.254/latest/meta-data"));
    }

    @Test void addressClassifierRejectsPrivateIpv4AndIpv6() throws Exception {
        for (String ip : new String[]{"10.0.0.1", "172.16.0.1", "192.168.1.1", "169.254.1.1", "fc00::1", "fe80::1"})
            assertFalse(PublicUrlPolicy.isPublic(InetAddress.getByName(ip)), ip);
        assertTrue(PublicUrlPolicy.isPublic(InetAddress.getByName("8.8.8.8")));
    }
}
