package com.dwinovo.numen.api.brain;

import com.dwinovo.numen.api.home.Home;
import com.dwinovo.numen.api.skin.SkinCache;
import com.dwinovo.numen.api.skin.SkinProfile;
import com.dwinovo.numen.api.skin.SkinRef;
import com.dwinovo.numen.api.store.Io;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 旧皮肤库搬进文件夹、缓存与同伴的绑定:服务器提供的、玩家自己的、同伴穿着的;可重来。 */
class SkinMigrationTest {

    private static final byte[] RED = {1, 2, 3};
    private static final byte[] BLUE = {4, 5, 6};

    @TempDir
    Path dir;

    private SkinCache cache() {
        return new SkinCache(new Home(dir.resolve("server"), Io.direct(e -> {
            throw e;
        })));
    }

    private void entry(String where, String name, SkinProfile skin) throws IOException {
        Path file = dir.resolve(where).resolve(name + ".json");
        Files.createDirectories(file.getParent());
        Files.writeString(file, skin.write(), StandardCharsets.UTF_8);
    }

    private long pngs() throws IOException {
        try (Stream<Path> files = Files.list(dir.resolve("skins"))) {
            return files.count();
        }
    }

    @Test
    void signedSkinsGoIntoTheCacheAndImagesBecomePngsNamedAfterTheEntry() throws IOException {
        entry("lib/my", "小红", new SkinProfile("slim", "v", "s", RED));
        entry("lib/my", "没签", new SkinProfile("classic", "", "", BLUE));
        entry("lib/server", "借", new SkinProfile("classic", "mojang", "m", null));
        SkinCache cache = cache();

        Map<String, SkinRef> ids = SkinMigration.library(dir.resolve("lib"), dir.resolve("skins"), cache, true);

        assertEquals(Map.of("my:小红", SkinRef.of(RED, "slim"),
                "server:借", SkinRef.of("mojang".getBytes(StandardCharsets.UTF_8), "classic")), ids,
                "没签过名的没有可穿的引用;没有原图的按签名数据记");
        assertEquals("v", cache.find(ids.get("my:小红")).value());
        assertEquals("m", cache.find(ids.get("server:借")).signature());
        assertArrayEquals(RED, Files.readAllBytes(dir.resolve("skins").resolve("小红.png")));
        assertArrayEquals(BLUE, Files.readAllBytes(dir.resolve("skins").resolve("没签.png")), "没签过的图也成了文件夹里的一张");
        assertEquals(2, pngs(), "没有原图的借来的皮肤不出现在文件夹里");
    }

    @Test
    void migratingTwiceAddsNothingAndADifferentImageUnderATakenNameGetsANewName() throws IOException {
        entry("lib/my", "小红", new SkinProfile("slim", "v", "s", RED));
        Files.createDirectories(dir.resolve("skins"));
        Files.write(dir.resolve("skins").resolve("小红.png"), BLUE);   // 玩家文件夹里本来就有个叫这名字的别的图
        SkinCache cache = cache();

        SkinMigration.library(dir.resolve("lib"), dir.resolve("skins"), cache, true);
        Map<String, SkinRef> again = SkinMigration.library(dir.resolve("lib"), dir.resolve("skins"), cache, true);

        assertArrayEquals(BLUE, Files.readAllBytes(dir.resolve("skins").resolve("小红.png")), "他自己的图原样");
        assertArrayEquals(RED, Files.readAllBytes(dir.resolve("skins").resolve("小红_2.png")));
        assertEquals(2, pngs(), "再搬一次不会多出文件");
        assertEquals(SkinRef.of(RED, "slim"), again.get("my:小红"));
    }

    @Test
    void aPlayersLibraryOnADedicatedServerKeepsItsImagesForTheHandover() throws IOException {
        entry("lib/my", "小红", new SkinProfile("slim", "v", "s", RED));
        SkinCache cache = cache();

        Map<String, SkinRef> ids = SkinMigration.library(dir.resolve("lib"), dir.resolve("skins"), cache, false);

        assertEquals("v", cache.find(ids.get("my:小红")).value(), "签好的数据照样进缓存");
        assertFalse(Files.exists(dir.resolve("skins")), "原图要交到他本机去,不放在服务器的文件夹里");
    }

    @Test
    void whatSheWearsIsRewrittenToAReferenceAndNothingElseIsTouched() throws IOException {
        SkinRef ref = SkinRef.of(RED, "slim");
        Path worn = dir.resolve("a").resolve("binding.json");
        Path alreadyNew = dir.resolve("b").resolve("binding.json");
        Path stale = dir.resolve("c").resolve("binding.json");
        Path none = dir.resolve("d").resolve("binding.json");
        for (Path file : List.of(worn, alreadyNew, stale, none)) {
            Files.createDirectories(file.getParent());
        }
        Files.writeString(worn, "{\"persona\":\"numen:小焰\",\"skin\":\"my:小红\"}");
        Files.writeString(alreadyNew, "{\"skin\":\"" + ref + "\"}");
        Files.writeString(stale, "{\"skin\":\"my:删掉了\"}");
        Files.writeString(none, "{\"persona\":\"numen:小焰\"}");

        int changed = SkinMigration.rebind(List.of(worn, alreadyNew, stale, none, dir.resolve("e").resolve("binding.json")),
                Map.of("my:小红", ref));

        JsonObject after = JsonParser.parseString(Files.readString(worn)).getAsJsonObject();
        assertEquals(2, changed);
        assertEquals(ref.toString(), after.get("skin").getAsString());
        assertEquals("numen:小焰", after.get("persona").getAsString());
        assertTrue(Files.readString(alreadyNew).contains(ref.toString()), "已经是引用的不动");
        assertFalse(Files.exists(stale), "指不到任何东西的旧名字去掉,绑定空了就不留这个文件");
        assertEquals("{\"persona\":\"numen:小焰\"}", Files.readString(none));
    }
}
