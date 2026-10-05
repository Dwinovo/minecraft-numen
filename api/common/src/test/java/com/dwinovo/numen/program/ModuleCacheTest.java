package com.dwinovo.numen.program;

import com.dwinovo.numen.script.Modules;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 服务端按内容缓存模块正文:指纹是内容的名字,按主人分开,总字节有上限,主人断线清掉。 */
class ModuleCacheTest {

    private static final UUID A = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID B = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private static String hash(String code) {
        return Modules.fingerprint(code);
    }

    @Test
    void aBodyIsFoundByItsFingerprintAndOnlyForItsOwner() {
        ModuleCache cache = new ModuleCache(1 << 20);
        cache.put(A, hash("-- one\nreturn {}"), "-- one\nreturn {}");
        assertEquals("-- one\nreturn {}", cache.get(A, hash("-- one\nreturn {}")));
        assertNull(cache.get(B, hash("-- one\nreturn {}")), "another owner's cache is another cache");
        assertNull(cache.get(A, "000000000000"));
    }

    @Test
    void aBodyThatDoesNotMatchItsFingerprintIsRefused() {
        ModuleCache cache = new ModuleCache(1 << 20);
        assertThrows(IllegalArgumentException.class, () -> cache.put(A, hash("-- one\nreturn {}"), "-- other\nreturn {}"));
        assertNull(cache.get(A, hash("-- one\nreturn {}")));
        assertEquals(0, cache.bytes(A));
    }

    @Test
    void whenFullTheLeastRecentlyUsedBodyGoesFirst() {
        String one = "-- 1" + "x".repeat(96);
        String two = "-- 2" + "x".repeat(96);
        String three = "-- 3" + "x".repeat(96);
        ModuleCache cache = new ModuleCache(250);   // 两份放得下,三份放不下
        cache.put(A, hash(one), one);
        cache.put(A, hash(two), two);
        assertEquals(one, cache.get(A, hash(one)), "using one makes two the least recent");
        cache.put(A, hash(three), three);

        assertEquals(one, cache.get(A, hash(one)));
        assertNull(cache.get(A, hash(two)));
        assertEquals(three, cache.get(A, hash(three)));
        assertTrue(cache.bytes(A) <= 250, "bytes " + cache.bytes(A));
    }

    @Test
    void theBodyJustStoredStaysEvenWhenItAloneIsOverTheLimit() {
        ModuleCache cache = new ModuleCache(10);
        String big = "-- big" + "x".repeat(100);
        cache.put(A, hash(big), big);
        assertEquals(big, cache.get(A, hash(big)), "the program using it must find it");
    }

    @Test
    void whenTheOwnerLeavesHisCacheIsGone() {
        ModuleCache cache = new ModuleCache(1 << 20);
        cache.put(A, hash("-- a\nreturn {}"), "-- a\nreturn {}");
        cache.put(B, hash("-- b\nreturn {}"), "-- b\nreturn {}");
        cache.drop(A);
        assertNull(cache.get(A, hash("-- a\nreturn {}")));
        assertEquals("-- b\nreturn {}", cache.get(B, hash("-- b\nreturn {}")));
        assertEquals(0, cache.bytes(A));
    }
}
