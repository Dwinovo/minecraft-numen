package com.dwinovo.numen.api.skin;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 什么图能当皮肤,什么网址服务端肯替玩家去取。 */
class SkinImageTest {

    @Test
    void aSkinIs64By64OrTheLegacy64By32Png() {
        SkinImage.check(SkinSigningTest.png(64, 64));
        SkinImage.check(SkinSigningTest.png(64, 32));
        assertEquals(SkinError.BAD_IMAGE, assertThrows(SkinError.class, () -> SkinImage.check(SkinSigningTest.png(128, 128))).code());
        assertEquals(SkinError.BAD_IMAGE, assertThrows(SkinError.class, () -> SkinImage.check(SkinSigningTest.png(64, 48))).code());
    }

    @Test
    void somethingThatIsNotAPngOrIsTooBigIsRefused() {
        assertThrows(SkinError.class, () -> SkinImage.check(new byte[40]));
        assertThrows(SkinError.class, () -> SkinImage.check(new byte[] {1, 2, 3}));
        assertThrows(SkinError.class, () -> SkinImage.check(new byte[SkinImage.MAX_BYTES + 1]));
    }

    @Test
    void onlyPublicAddressesAreFetched() throws Exception {
        assertTrue(SkinImage.isPublic(InetAddress.getByName("8.8.8.8")));
        for (String inside : new String[] {"127.0.0.1", "10.1.2.3", "192.168.0.5", "172.16.0.1", "169.254.1.1", "0.0.0.0",
                "::1", "fd00::1", "224.0.0.1"}) {
            assertFalse(SkinImage.isPublic(InetAddress.getByName(inside)), inside);
        }
        assertFalse(SkinImage.publicHost(URI.create("http://127.0.0.1/skin.png")));
        assertFalse(SkinImage.publicHost(URI.create("http://[::1]/skin.png")));
    }

    @Test
    void anAddressThatIsNotHttpOrNotAllowedNeverLeavesTheMachine() {
        for (String url : new String[] {"file:///etc/passwd", "ftp://example.com/a.png", "http://127.0.0.1/a.png", "not a url",
                "http://localhost/a.png"}) {
            assertEquals(SkinError.BAD_IMAGE, assertThrows(SkinError.class, () -> SkinImage.fetch(url)).code(), url);
        }
        assertEquals(SkinError.BAD_IMAGE,
                assertThrows(SkinError.class, () -> SkinImage.fetchTexture("http://evil.example/a.png")).code(),
                "Mojang 的图只认它自己的图床");
    }
}
