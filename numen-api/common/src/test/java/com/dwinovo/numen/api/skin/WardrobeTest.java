package com.dwinovo.numen.api.skin;

import com.dwinovo.numen.api.entity.MojangSkins;
import com.dwinovo.numen.api.home.Home;
import com.dwinovo.numen.api.store.Io;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 客户端换皮肤的流程:拿到原图、查本机缓存、没签过才签、签好交给服务端穿上。签过的当场交出去,不排队;借正版玩家的皮肤不经 MineSkin;
 * 服务端不收的原因回到结局里。网络与服务端都是假的。
 */
class WardrobeTest {

    private static final UUID HER = UUID.randomUUID();

    private final List<String> events = new CopyOnWriteArrayList<>();
    private final AtomicInteger signed = new AtomicInteger();
    private final AtomicInteger borrowed = new AtomicInteger();
    private SkinSigning signing;
    private Wardrobe wardrobe;
    private SkinCache cache;
    /** 服务端对"穿上"的答复:{@code null} 是收下了。 */
    private String serverAnswer;
    private CompletableFuture<SkinSigning.Outcome> last;

    private void wardrobe(Path dir) {
        cache = new SkinCache(new Home(dir, Io.direct(e -> {
            throw e;
        })));
        signing = new SkinSigning(new SkinSigning.Backend() {
            @Override
            public MineSkinClient.Signed sign(byte[] png, String variant, String apiKey) {
                signed.incrementAndGet();
                return new MineSkinClient.Signed("v-" + variant, "s");
            }

            @Override
            public byte[] fetch(String url) {
                return SkinFixtures.real(false);
            }

            @Override
            public MojangSkinLookup.Result lookup(String playerName) {
                borrowed.incrementAndGet();
                return new MojangSkinLookup.Result(new MojangSkins.Skin(TEXTURES, "mojang-sig"), null);
            }

            @Override
            public byte[] texture(String url) {
                return SkinFixtures.real(true);
            }
        }, () -> null, Runnable::run, cache, ms -> { });
        wardrobe = new Wardrobe(signing, cache);
    }

    private static final String TEXTURES = Base64.getEncoder().encodeToString(
            "{\"textures\":{\"SKIN\":{\"url\":\"http://textures.minecraft.net/a\",\"metadata\":{\"model\":\"slim\"}}}}"
                    .getBytes(StandardCharsets.UTF_8));

    private final Wardrobe.Dress dress = new Wardrobe.Dress() {
        @Override
        public void wear(UUID companion, SkinRef ref, SkinProfile skin, Consumer<String> answer) {
            events.add("wear " + ref + " " + skin.value());
            answer.accept(serverAnswer);
        }

        @Override
        public void takeOff(UUID companion, Consumer<String> answer) {
            events.add("off");
            answer.accept(serverAnswer);
        }
    };

    private SkinSigning.Listener listener() {
        last = new CompletableFuture<>();
        return new SkinSigning.Listener() {
            @Override
            public void queued(int position) {
                events.add("queued " + position);
            }

            @Override
            public void done(SkinSigning.Outcome outcome) {
                events.add("done " + outcome.ok());
                last.complete(outcome);
            }
        };
    }

    @AfterEach
    void close() {
        if (signing != null) {
            signing.close();
        }
    }

    @Test
    void anImageIsSignedOnceAndHandedToTheServerThenWornAtOnceFromTheCache(@TempDir Path dir) throws Exception {
        wardrobe(dir);
        byte[] png = SkinFixtures.real(false);

        long ticket = wardrobe.wear(HER, "Steve", new SkinSigning.Source.Image(png), null, dress, listener());
        last.get(5, TimeUnit.SECONDS);

        String wear = "wear " + SkinRef.of(png, "classic") + " v-classic";
        assertTrue(ticket > 0, "没签过的排进队");
        assertTrue(events.indexOf(wear) >= 0 && events.indexOf(wear) < events.indexOf("done true"),
                "先交给服务端,服务端答了才算办成: " + events);

        events.clear();
        assertEquals(0, wardrobe.wear(HER, "Steve", new SkinSigning.Source.Image(png), null, dress, listener()),
                "签过的不排队");
        assertEquals(List.of(wear, "done true"), events);
        assertEquals(1, signed.get(), "同一张图签一次就够");
    }

    @Test
    void aSignedSkinIsStillInTheLocalCacheForTheNextMachineSession(@TempDir Path dir) throws Exception {
        wardrobe(dir);
        byte[] png = SkinFixtures.real(false);
        wardrobe.wear(HER, "Steve", new SkinSigning.Source.Image(png), null, dress, listener());
        last.get(5, TimeUnit.SECONDS);
        signing.close();

        wardrobe(dir);   // 同一个目录再起一份:换服务器、重开游戏都一样
        events.clear();
        assertEquals(0, wardrobe.wear(HER, "Steve", new SkinSigning.Source.Image(png), null, dress, listener()));

        assertEquals(List.of("wear " + SkinRef.of(png, "classic") + " v-classic", "done true"), events);
        assertEquals(1, signed.get());
    }

    @Test
    void aBorrowedPlayerSkinIsAlreadySignedByMojangAndNeverGoesToMineSkin(@TempDir Path dir) throws Exception {
        wardrobe(dir);

        wardrobe.wear(HER, "Notch", new SkinSigning.Source.Player("Notch"), null, dress, listener());
        SkinSigning.Outcome outcome = last.get(5, TimeUnit.SECONDS);

        assertTrue(outcome.ok());
        assertEquals(0, signed.get(), "借来的不经 MineSkin");
        assertEquals(1, borrowed.get());
        assertTrue(events.contains("wear " + outcome.ref() + " " + TEXTURES), events.toString());
        assertEquals("slim", outcome.ref().variant(), "手臂模型以 Mojang 的签名数据为准");
    }

    @Test
    void anImageFromAUrlGoesThroughTheQueueToo(@TempDir Path dir) throws Exception {
        wardrobe(dir);

        wardrobe.wear(HER, "u", new SkinSigning.Source.Url("http://example.com/a.png"), null, dress, listener());

        assertTrue(last.get(5, TimeUnit.SECONDS).ok());
        assertEquals(1, signed.get());
        assertTrue(events.stream().anyMatch(e -> e.startsWith("wear ")), events.toString());
    }

    @Test
    void whenTheServerRefusesTheOutcomeCarriesItsReason(@TempDir Path dir) throws Exception {
        wardrobe(dir);
        serverAnswer = SkinError.SIGNATURE_REJECTED;

        wardrobe.wear(HER, "Steve", new SkinSigning.Source.Image(SkinFixtures.real(false)), null, dress, listener());
        SkinSigning.Outcome outcome = last.get(5, TimeUnit.SECONDS);

        assertFalse(outcome.ok());
        assertEquals(SkinError.SIGNATURE_REJECTED, outcome.problem().code());
    }

    @Test
    void anImageThatIsNotASkinIsRefusedBeforeAnythingHappens(@TempDir Path dir) {
        wardrobe(dir);

        assertEquals(SkinError.BAD_IMAGE, assertThrows(SkinError.class, () -> wardrobe.wear(HER, "big",
                new SkinSigning.Source.Image(SkinFixtures.header(512, 512)), null, dress, listener())).code());
        assertTrue(events.isEmpty());
        assertTrue(wardrobe.pending().isEmpty());
    }
}
