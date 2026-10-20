package com.dwinovo.numen.api.skin;

import com.dwinovo.numen.api.entity.MojangSkins;
import com.dwinovo.numen.api.home.Home;
import com.dwinovo.numen.api.store.Io;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 给同伴换皮肤的流程:拿到原图、查缓存、没签过才签、套上;签过的当场穿上。网络是假的。 */
class WardrobeTest {

    private static final UUID OWNER = UUID.randomUUID();
    private static final UUID HER = UUID.randomUUID();

    private final List<String> events = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final AtomicInteger signed = new AtomicInteger();
    private SkinSigning signing;
    private Wardrobe wardrobe;
    private Path skins;
    private CompletableFuture<SkinSigning.Outcome> last;

    private Wardrobe wardrobe(Path dir, SkinSigning.Backend backend) throws IOException {
        skins = dir.resolve("skins");
        Files.createDirectories(skins);
        SkinCache cache = new SkinCache(new Home(dir.resolve("server"), Io.direct(e -> {
            throw e;
        })));
        signing = new SkinSigning(backend, () -> null, Runnable::run, owner -> { }, cache, ms -> { });
        wardrobe = new Wardrobe(signing, cache, new SkinFolder(skins), new Wardrobe.Dress() {
            @Override
            public boolean wear(UUID companion, SkinRef ref, SkinProfile skin) {
                events.add("wear " + ref + " " + skin.value());
                return true;
            }

            @Override
            public void takeOff(UUID companion) {
                events.add("off");
            }
        });
        return wardrobe;
    }

    private SkinSigning.Backend backend() {
        return new SkinSigning.Backend() {
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
                return new MojangSkinLookup.Result(new MojangSkins.Skin("m", "s"), null);
            }

            @Override
            public byte[] texture(String url) {
                return SkinFixtures.header(64, 64);
            }
        };
    }

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
    void aFileInTheServersFolderIsSignedOnceThenWornAtOnceAfterwards(@TempDir Path dir) throws Exception {
        wardrobe(dir, backend());
        byte[] png = SkinFixtures.real(false);
        Files.write(skins.resolve("Steve.png"), png);

        long ticket = wardrobe.wearFile(OWNER, HER, "Steve", null, listener());
        last.get(5, TimeUnit.SECONDS);

        assertTrue(ticket > 0, "没签过的排进队");
        String wear = "wear " + SkinRef.of(png, "classic") + " v-classic";
        assertTrue(events.contains("queued 1"), events.toString());
        assertTrue(events.indexOf(wear) >= 0 && events.indexOf(wear) < events.indexOf("done true"),
                "先套上,再告诉说话的人办成了: " + events);

        events.clear();
        assertEquals(0, wardrobe.wearFile(OWNER, HER, "Steve", null, listener()), "签过的不排队");
        assertEquals(List.of("wear " + SkinRef.of(png, "classic") + " v-classic", "done true"), events);
        assertEquals(1, signed.get(), "同一张图签一次就够");
    }

    @Test
    void anImageFromAUrlGoesThroughTheQueueAndAFailureDressesNobody(@TempDir Path dir) throws Exception {
        SkinSigning.Backend ok = backend();
        wardrobe(dir, new SkinSigning.Backend() {
            @Override
            public MineSkinClient.Signed sign(byte[] png, String variant, String apiKey) {
                throw SkinError.rateLimited("MineSkin", 10_000);
            }

            @Override
            public byte[] fetch(String url) {
                return ok.fetch(url);
            }

            @Override
            public MojangSkinLookup.Result lookup(String playerName) {
                return ok.lookup(playerName);
            }

            @Override
            public byte[] texture(String url) {
                return ok.texture(url);
            }
        });

        wardrobe.wear(OWNER, HER, "u", new SkinSigning.Source.Url("http://example.com/a.png"), null, listener());
        SkinSigning.Outcome outcome = last.get(5, TimeUnit.SECONDS);

        assertFalse(outcome.ok());
        assertEquals(SkinError.RATE_LIMITED, outcome.problem().code());
        assertFalse(events.stream().anyMatch(e -> e.startsWith("wear")), "没签成就不换:" + events);
    }

    @Test
    void anImageThatIsNotASkinIsRefusedBeforeAnythingHappens(@TempDir Path dir) throws Exception {
        wardrobe(dir, backend());
        Files.write(skins.resolve("big.png"), SkinFixtures.header(512, 512));

        assertEquals(SkinError.BAD_IMAGE, assertThrows(SkinError.class,
                () -> wardrobe.wearFile(OWNER, HER, "big", null, listener())).code());
        assertEquals(SkinError.BAD_IMAGE, assertThrows(SkinError.class,
                () -> wardrobe.wearFile(OWNER, HER, "none", null, listener())).code());
        assertTrue(events.isEmpty());
        assertTrue(signing.pendingOf(OWNER).isEmpty());
    }

    @Test
    void takingTheSkinOffGoesBackToTheDefault(@TempDir Path dir) throws Exception {
        wardrobe(dir, backend());

        wardrobe.takeOff(HER);

        assertEquals(List.of("off"), events);
    }
}
