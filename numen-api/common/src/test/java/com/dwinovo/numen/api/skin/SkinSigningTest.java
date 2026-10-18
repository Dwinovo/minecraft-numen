package com.dwinovo.numen.api.skin;

import com.dwinovo.numen.api.entity.MojangSkins;
import com.dwinovo.numen.api.library.EntryId;
import com.dwinovo.numen.api.library.LibraryHub;
import com.dwinovo.numen.api.library.Shelf;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 皮肤签名队:一个一个发、排位看得见、被限流就等再发同一张、失败留在队里等玩家撤掉、签好才写进货架。网络是假的。
 */
class SkinSigningTest {

    private static final UUID ME = UUID.randomUUID();
    private static final UUID YOU = UUID.randomUUID();
    private static final byte[] PNG = png(64, 64);

    private SkinSigning signing;

    @AfterEach
    void close() {
        if (signing != null) {
            signing.close();
        }
    }

    static byte[] png(int width, int height) {
        byte[] png = new byte[33];
        byte[] magic = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};
        System.arraycopy(magic, 0, png, 0, 8);
        png[12] = 'I';
        png[13] = 'H';
        png[14] = 'D';
        png[15] = 'R';
        for (int i = 0; i < 4; i++) {
            png[16 + i] = (byte) (width >> (24 - 8 * i));
            png[20 + i] = (byte) (height >> (24 - 8 * i));
        }
        return png;
    }

    /** 签名函数由用例给;其余三件事用不到。 */
    private interface Signer {
        MineSkinClient.Signed sign(byte[] png, String variant, String apiKey);
    }

    private SkinSigning queue(Signer signer, List<Integer> slept) {
        signing = new SkinSigning(new SkinSigning.Backend() {
            @Override
            public MineSkinClient.Signed sign(byte[] png, String variant, String apiKey) {
                return signer.sign(png, variant, apiKey);
            }

            @Override
            public byte[] fetch(String url) {
                return PNG;
            }

            @Override
            public MojangSkinLookup.Result lookup(String playerName) {
                return new MojangSkinLookup.Result(new MojangSkins.Skin("mojang", "sig"), null);
            }

            @Override
            public byte[] texture(String url) {
                throw SkinError.of(SkinError.NETWORK, "textures");
            }
        }, () -> "key", Runnable::run, owner -> { }, (request, outcome) -> { },
                ms -> slept.add((int) (ms / 1000)));
        return signing;
    }

    private static SkinSigning.Request request(UUID owner, String name, Shelf shelf, EntryId replaces) {
        return new SkinSigning.Request(owner, name, SkinProfile.VARIANT_CLASSIC, new SkinSigning.Source.Image(PNG), shelf,
                EntryId.MINE, replaces);
    }

    private static Shelf shelf(Path dir) {
        return LibraryHub.headless(dir).library(SkinProfile.KIND, ME).mine();
    }

    private static final class Wait implements SkinSigning.Listener {
        final CompletableFuture<SkinSigning.Outcome> outcome = new CompletableFuture<>();
        final List<Integer> positions = new CopyOnWriteArrayList<>();
        final List<Integer> waits = new CopyOnWriteArrayList<>();

        @Override
        public void queued(int position) {
            positions.add(position);
        }

        @Override
        public void waiting(int seconds) {
            waits.add(seconds);
        }

        @Override
        public void done(SkinSigning.Outcome result) {
            outcome.complete(result);
        }

        SkinSigning.Outcome get() throws Exception {
            return outcome.get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void aSignedSkinIsStoredOnceWithItsImageAndTheKeyIsReadFreshEachTime(@TempDir Path dir) throws Exception {
        List<String> keys = new CopyOnWriteArrayList<>();
        queue((png, variant, key) -> {
            keys.add(key);
            return new MineSkinClient.Signed("v-" + variant, "s");
        }, new ArrayList<>());
        Shelf shelf = shelf(dir);
        Wait wait = new Wait();

        assertTrue(signing.submit(request(ME, "小红", shelf, null), wait) > 0);
        SkinSigning.Outcome outcome = wait.get();

        assertTrue(outcome.ok());
        assertEquals(EntryId.mine("小红"), outcome.id());
        SkinProfile stored = SkinProfile.read(shelf.get(outcome.id()).text());
        assertEquals("v-classic", stored.value());
        assertEquals(PNG.length, stored.png().length, "条目存着原图");
        assertEquals(List.of("key"), keys);
    }

    @Test
    void whoIsWaitingKnowsTheirPlaceInLine(@TempDir Path dir) throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch started = new CountDownLatch(1);
        queue((png, variant, key) -> {
            started.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return new MineSkinClient.Signed("v", "s");
        }, new ArrayList<>());
        Shelf shelf = shelf(dir);
        Wait first = new Wait();
        Wait second = new Wait();

        signing.submit(request(ME, "一", shelf, null), first);
        assertTrue(started.await(5, TimeUnit.SECONDS));
        signing.submit(request(YOU, "二", shelf, null), second);

        assertEquals(List.of(1), first.positions);
        assertEquals(List.of(2), second.positions, "前面还有一个在签");
        assertEquals(SkinSigning.State.SIGNING, signing.pendingOf(ME).get(0).state());
        assertEquals(SkinSigning.State.QUEUED, signing.pendingOf(YOU).get(0).state());
        assertEquals(2, signing.pendingOf(YOU).get(0).position());

        release.countDown();
        assertTrue(first.get().ok());
        assertTrue(second.get().ok());
        assertTrue(signing.pendingOf(YOU).isEmpty());
    }

    @Test
    void aRateLimitMakesItWaitAndSendTheSameImageAgain(@TempDir Path dir) throws Exception {
        AtomicInteger calls = new AtomicInteger();
        List<Integer> slept = new CopyOnWriteArrayList<>();
        queue((png, variant, key) -> {
            if (calls.incrementAndGet() < 3) {
                throw SkinError.rateLimited("MineSkin", calls.get() == 1 ? 7 : 0);
            }
            return new MineSkinClient.Signed("v", "s");
        }, slept);
        Wait wait = new Wait();

        signing.submit(request(ME, "慢", shelf(dir), null), wait);

        assertTrue(wait.get().ok());
        assertEquals(3, calls.get());
        assertEquals(List.of(7, SkinSigning.DEFAULT_WAIT_SECONDS), slept, "对方说多久等多久,没说等默认的");
        assertEquals(slept, wait.waits, "等的人被告知了");
    }

    @Test
    void aRateLimitThatNeverLiftsEndsAsAFailureThatStaysUntilItIsDismissed(@TempDir Path dir) throws Exception {
        queue((png, variant, key) -> {
            throw SkinError.rateLimited("MineSkin", 120);
        }, new ArrayList<>());
        Wait wait = new Wait();

        long ticket = signing.submit(request(ME, "没完", shelf(dir), null), wait);
        SkinSigning.Outcome outcome = wait.get();

        assertFalse(outcome.ok());
        assertEquals(SkinError.RATE_LIMITED, outcome.problem().code());
        List<SkinSigning.Pending> left = signing.pendingOf(ME);
        assertEquals(1, left.size());
        assertNotNull(left.get(0).failure(), "失败留在队里让玩家看到");
        assertTrue(signing.cancel(ME, ticket));
        assertTrue(signing.pendingOf(ME).isEmpty());
        assertFalse(signing.cancel(YOU, ticket), "别人撤不了你的");
    }

    @Test
    void aQueuedSkinCanBeCancelledAndOneOwnerCannotHoldTheWholeQueue(@TempDir Path dir) throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        queue((png, variant, key) -> {
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return new MineSkinClient.Signed("v", "s");
        }, new ArrayList<>());
        Shelf shelf = shelf(dir);
        List<Long> tickets = new ArrayList<>();
        List<Wait> waits = new ArrayList<>();
        for (int i = 0; i < SkinSigning.MAX_PENDING_PER_OWNER; i++) {
            waits.add(new Wait());
            tickets.add(signing.submit(request(ME, "n" + i, shelf, null), waits.get(i)));
        }

        assertEquals(-1, signing.submit(request(ME, "多的", shelf, null), new Wait()), "占满了");
        Wait yours = new Wait();
        assertTrue(signing.submit(request(YOU, "别人的", shelf, null), yours) > 0, "别人照排");
        assertTrue(signing.cancel(ME, tickets.get(2)));
        assertEquals(SkinSigning.MAX_PENDING_PER_OWNER - 1, signing.pendingOf(ME).size());
        release.countDown();
        for (int i = 0; i < waits.size(); i++) {
            if (i != 2) {
                waits.get(i).get();   // 都签完再收场,临时目录才删得掉
            }
        }
        yours.get();
    }

    @Test
    void anImageThatIsNotASkinIsRefusedOnTheSpot(@TempDir Path dir) {
        queue((png, variant, key) -> new MineSkinClient.Signed("v", "s"), new ArrayList<>());
        SkinSigning.Request big = new SkinSigning.Request(ME, "大", SkinProfile.VARIANT_CLASSIC,
                new SkinSigning.Source.Image(png(128, 128)), shelf(dir), EntryId.MINE, null);

        SkinError refused = org.junit.jupiter.api.Assertions.assertThrows(SkinError.class,
                () -> signing.submit(big, new Wait()));
        assertEquals(SkinError.BAD_IMAGE, refused.code());
        assertTrue(signing.pendingOf(ME).isEmpty());
    }

    @Test
    void replacingKeepsTheNameOrMovesTheEntry(@TempDir Path dir) throws Exception {
        queue((png, variant, key) -> new MineSkinClient.Signed("v-" + variant, "s"), new ArrayList<>());
        Shelf shelf = shelf(dir);
        Wait made = new Wait();
        signing.submit(request(ME, "旧", shelf, null), made);
        EntryId old = made.get().id();

        Wait same = new Wait();
        signing.submit(new SkinSigning.Request(ME, "旧", SkinProfile.VARIANT_SLIM, new SkinSigning.Source.Image(PNG), shelf,
                EntryId.MINE, old), same);
        assertEquals(old, same.get().id(), "同名原地换");
        assertEquals("v-slim", SkinProfile.read(shelf.get(old).text()).value());

        Wait renamed = new Wait();
        signing.submit(request(ME, "新", shelf, old), renamed);
        SkinSigning.Outcome moved = renamed.get();
        assertEquals(EntryId.mine("新"), moved.id());
        assertEquals(old, moved.replaced());
        assertNull(shelf.get(old), "旧名字删了");
    }

    @Test
    void aBorrowedPlayerSkinIsNotSentToMineSkinAndWithoutAPictureStillStores(@TempDir Path dir) throws Exception {
        AtomicInteger sent = new AtomicInteger();
        queue((png, variant, key) -> {
            sent.incrementAndGet();
            return new MineSkinClient.Signed("v", "s");
        }, new ArrayList<>());
        Shelf shelf = shelf(dir);
        Wait wait = new Wait();

        signing.submit(new SkinSigning.Request(ME, "借", SkinProfile.VARIANT_CLASSIC, new SkinSigning.Source.Player("Notch"),
                shelf, EntryId.MINE, null), wait);

        SkinSigning.Outcome outcome = wait.get();
        assertTrue(outcome.ok());
        assertEquals(0, sent.get());
        SkinProfile stored = SkinProfile.read(shelf.get(outcome.id()).text());
        assertEquals("mojang", stored.value());
        assertEquals(0, stored.png().length, "取不到图床的图,条目没有预览但照样能用");
    }
}
