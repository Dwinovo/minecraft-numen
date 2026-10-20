package com.dwinovo.numen.api.skin;

import com.dwinovo.numen.api.home.Home;
import com.dwinovo.numen.api.store.Io;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** 签名缓存按内容寻址:同图同粗细命中,同图换粗细、改了图都不命中;落了盘的下次启动还在。 */
class SkinCacheTest {

    private static final byte[] IMAGE = SkinFixtures.header(64, 64);

    private static SkinCache cacheAt(Path dir) {
        return new SkinCache(new Home(dir, Io.direct(e -> {
            throw e;
        })));
    }

    @Test
    void theSameImageWithTheSameArmsHitsAndEverythingElseMisses(@TempDir Path dir) {
        SkinCache cache = cacheAt(dir);
        SkinProfile signed = new SkinProfile("slim", "value", "signature", IMAGE);
        cache.put(SkinRef.of(IMAGE, "slim"), signed);

        assertEquals("value", cache.find(SkinRef.of(IMAGE.clone(), "slim")).value(), "内容一样就是同一张,不看是不是同一个数组");
        assertNull(cache.find(SkinRef.of(IMAGE, "classic")), "同图换粗细要重签");
        byte[] edited = IMAGE.clone();
        edited[30] = 1;
        assertNull(cache.find(SkinRef.of(edited, "slim")), "改了图要重签");
    }

    @Test
    void whatWasSignedIsStillThereAfterARestart(@TempDir Path dir) throws IOException {
        SkinRef ref = SkinRef.of(IMAGE, "classic");
        cacheAt(dir).put(ref, new SkinProfile("classic", "value", "signature", IMAGE));

        SkinProfile back = cacheAt(dir).find(ref);

        assertEquals("signature", back.signature());
        assertEquals(IMAGE.length, back.png().length, "原图也存着:换粗细时拿它重签");
        assertEquals(ref + ".json", Files.list(dir.resolve("skin-cache")).findFirst().orElseThrow().getFileName().toString());
    }

    @Test
    void anUnreadableEntryIsTreatedAsNeverSigned(@TempDir Path dir) throws IOException {
        SkinRef ref = SkinRef.of(IMAGE, "classic");
        Files.createDirectories(dir.resolve("skin-cache"));
        Files.writeString(dir.resolve("skin-cache").resolve(ref + ".json"), "{坏的");

        assertNull(cacheAt(dir).find(ref));
    }

    @Test
    void aRefIsHashAndArmsAndReadsBack() {
        SkinRef ref = SkinRef.of(IMAGE, "slim");

        assertEquals(ref, SkinRef.parse(ref.toString()));
        assertEquals(64, ref.hash().length(), "完整的 SHA-256");
        assertNotEquals(ref, SkinRef.of(IMAGE, "classic"));
        assertNull(SkinRef.parse("my:旧条目"));
        assertNull(SkinRef.parse(ref.hash() + "-wide"));
        assertNull(SkinRef.parse(null));
    }
}
