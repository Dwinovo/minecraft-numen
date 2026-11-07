package com.dwinovo.numen.api.skin;

import com.dwinovo.numen.api.entity.MojangSkins;
import com.dwinovo.numen.api.home.Home;
import com.dwinovo.numen.api.store.Io;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 皮肤签名队:一个一个发、排位看得见、被限流就等再发同一张、失败留在队里等玩家撤掉;拿到原图先问缓存,签过的不再联网,
 * 签好的存进缓存。网络是假的。
 */
class SkinSigningTest {

    private static final UUID ME = UUID.randomUUID();
    private static final UUID YOU = UUID.randomUUID();
    private static final byte[] PNG = SkinFixtures.header(64, 64);

    private SkinSigning signing;

    @AfterEach
    void close() {
        if (signing != null) {
            signing.close();
        }
    }

    /** 签名函数由用例给;其余几件事有缺省。 */
    private interface Signer {
        MineSkinClient.Signed sign(byte[] png, String variant, String apiKey);
    }

    private SkinCache cache;

    private SkinSigning queue(Path dir, Signer signer, List<Integer> slept) {
        return queue(dir, signer, slept, new MojangSkinLookup.Result(new MojangSkins.Skin("mojang", "sig"), null),
                url -> {
                    throw SkinError.of(SkinError.NETWORK, "textures");
                });
    }

    private SkinSigning queue(Path dir, Signer signer, List<Integer> slept, MojangSkinLookup.Result lookup,
                              java.util.function.Function<String, byte[]> texture) {
        cache = new SkinCache(new Home(dir, Io.direct(e -> {
            throw e;
        })));
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
                return lookup;
            }

            @Override
            public byte[] texture(String url) {
                return texture.apply(url);
            }
        }, () -> "key", Runnable::run, owner -> { }, cache, ms -> slept.add((int) (ms / 1000)));
        return signing;
    }

    private static SkinSigning.Request request(UUID owner, String name) {
        return new SkinSigning.Request(owner, name, SkinProfile.VARIANT_CLASSIC, new SkinSigning.Source.Image(PNG));
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
    void aSignedSkinGoesIntoTheCacheWithItsImageAndTheKeyIsReadFreshEachTime(@TempDir Path dir) throws Exception {
        List<String> keys = new CopyOnWriteArrayList<>();
        queue(dir, (png, variant, key) -> {
            keys.add(key);
            return new MineSkinClient.Signed("v-" + variant, "s");
        }, new ArrayList<>());
        Wait wait = new Wait();

        assertTrue(signing.submit(request(ME, "小红"), wait) > 0);
        SkinSigning.Outcome outcome = wait.get();

        assertTrue(outcome.ok());
        assertEquals(SkinRef.of(PNG, SkinProfile.VARIANT_CLASSIC), outcome.ref());
        assertEquals("v-classic", outcome.skin().value());
        SkinProfile stored = cache.find(outcome.ref());
        assertEquals("v-classic", stored.value());
        assertEquals(PNG.length, stored.png().length, "缓存存着原图,换手臂模型时用它重签");
        assertEquals(List.of("key"), keys);
    }

    @Test
    void theSameImageAndArmModelIsSignedOnceAnotherArmModelOrAnotherImageIsSignedAgain(@TempDir Path dir) throws Exception {
        AtomicInteger calls = new AtomicInteger();
        queue(dir, (png, variant, key) -> {
            calls.incrementAndGet();
            return new MineSkinClient.Signed("v-" + variant, "s");
        }, new ArrayList<>());

        Wait first = new Wait();
        signing.submit(request(ME, "甲"), first);
        first.get();
        Wait again = new Wait();
        signing.submit(request(YOU, "乙"), again);
        assertTrue(again.get().ok());
        assertEquals(1, calls.get(), "同图同粗细命中缓存,不联网");

        Wait slim = new Wait();
        signing.submit(new SkinSigning.Request(ME, "甲", SkinProfile.VARIANT_SLIM, new SkinSigning.Source.Image(PNG)), slim);
        assertEquals("v-slim", slim.get().skin().value());
        assertEquals(2, calls.get(), "同图换粗细要重签");

        byte[] edited = PNG.clone();
        edited[30] = 7;
        Wait changed = new Wait();
        signing.submit(new SkinSigning.Request(ME, "甲", SkinProfile.VARIANT_CLASSIC, new SkinSigning.Source.Image(edited)),
                changed);
        assertTrue(changed.get().ok());
        assertEquals(3, calls.get(), "改了图要重签");
    }

    @Test
    void withoutAnArmModelTheImageDecidesIt(@TempDir Path dir) throws Exception {
        queue(dir, (png, variant, key) -> new MineSkinClient.Signed("v-" + variant, "s"), new ArrayList<>());
        Wait thin = new Wait();
        signing.submit(new SkinSigning.Request(ME, "细", null, new SkinSigning.Source.Image(SkinFixtures.real(true))), thin);
        Wait wide = new Wait();
        signing.submit(new SkinSigning.Request(ME, "粗", null, new SkinSigning.Source.Image(SkinFixtures.real(false))), wide);

        assertEquals("v-slim", thin.get().skin().value());
        assertEquals("v-classic", wide.get().skin().value());
    }

    @Test
    void whoIsWaitingKnowsTheirPlaceInLine(@TempDir Path dir) throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch started = new CountDownLatch(1);
        queue(dir, (png, variant, key) -> {
            started.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return new MineSkinClient.Signed("v", "s");
        }, new ArrayList<>());
        Wait first = new Wait();
        Wait second = new Wait();

        signing.submit(request(ME, "一"), first);
        assertTrue(started.await(5, TimeUnit.SECONDS));
        signing.submit(new SkinSigning.Request(YOU, "二", SkinProfile.VARIANT_SLIM, new SkinSigning.Source.Image(PNG)), second);

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
        queue(dir, (png, variant, key) -> {
            if (calls.incrementAndGet() < 3) {
                throw SkinError.rateLimited("MineSkin", calls.get() == 1 ? 7 : 0);
            }
            return new MineSkinClient.Signed("v", "s");
        }, slept);
        Wait wait = new Wait();

        signing.submit(request(ME, "慢"), wait);

        assertTrue(wait.get().ok());
        assertEquals(3, calls.get());
        assertEquals(List.of(7, SkinSigning.DEFAULT_WAIT_SECONDS), slept, "对方说多久等多久,没说等默认的");
        assertEquals(slept, wait.waits, "等的人被告知了");
    }

    @Test
    void aRateLimitThatNeverLiftsEndsAsAFailureThatStaysUntilItIsDismissed(@TempDir Path dir) throws Exception {
        queue(dir, (png, variant, key) -> {
            throw SkinError.rateLimited("MineSkin", 120);
        }, new ArrayList<>());
        Wait wait = new Wait();

        long ticket = signing.submit(request(ME, "没完"), wait);
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
        queue(dir, (png, variant, key) -> {
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return new MineSkinClient.Signed("v", "s");
        }, new ArrayList<>());
        List<Long> tickets = new ArrayList<>();
        List<Wait> waits = new ArrayList<>();
        for (int i = 0; i < SkinSigning.MAX_PENDING_PER_OWNER; i++) {
            waits.add(new Wait());
            tickets.add(signing.submit(request(ME, "n" + i), waits.get(i)));
        }

        assertEquals(-1, signing.submit(request(ME, "多的"), new Wait()), "占满了");
        Wait yours = new Wait();
        assertTrue(signing.submit(request(YOU, "别人的"), yours) > 0, "别人照排");
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
        queue(dir, (png, variant, key) -> new MineSkinClient.Signed("v", "s"), new ArrayList<>());
        SkinSigning.Request big = new SkinSigning.Request(ME, "大", SkinProfile.VARIANT_CLASSIC,
                new SkinSigning.Source.Image(SkinFixtures.header(128, 128)));

        SkinError refused = assertThrows(SkinError.class, () -> signing.submit(big, new Wait()));
        assertEquals(SkinError.BAD_IMAGE, refused.code());
        assertTrue(signing.pendingOf(ME).isEmpty());
    }

    @Test
    void aBorrowedPlayerSkinIsNotSentToMineSkinAndIsCachedUnderItsPicture(@TempDir Path dir) throws Exception {
        AtomicInteger sent = new AtomicInteger();
        String textures = Base64.getEncoder().encodeToString(
                "{\"textures\":{\"SKIN\":{\"url\":\"http://textures.minecraft.net/a\",\"metadata\":{\"model\":\"slim\"}}}}"
                        .getBytes(StandardCharsets.UTF_8));
        queue(dir, (png, variant, key) -> {
            sent.incrementAndGet();
            return new MineSkinClient.Signed("v", "s");
        }, new ArrayList<>(), new MojangSkinLookup.Result(new MojangSkins.Skin(textures, "sig"), null), url -> PNG);
        Wait wait = new Wait();

        signing.submit(new SkinSigning.Request(ME, "借", null, new SkinSigning.Source.Player("Notch")), wait);

        SkinSigning.Outcome outcome = wait.get();
        assertTrue(outcome.ok());
        assertEquals(0, sent.get());
        assertEquals(SkinRef.of(PNG, SkinProfile.VARIANT_SLIM), outcome.ref(), "手臂模型以 Mojang 的签名数据为准");
        assertEquals(textures, cache.find(outcome.ref()).value());
    }

    @Test
    void aBorrowedSkinWhosePictureCannotBeFetchedIsAFailure(@TempDir Path dir) throws Exception {
        String textures = Base64.getEncoder().encodeToString(
                "{\"textures\":{\"SKIN\":{\"url\":\"http://textures.minecraft.net/a\"}}}".getBytes(StandardCharsets.UTF_8));
        queue(dir, (png, variant, key) -> new MineSkinClient.Signed("v", "s"), new ArrayList<>(),
                new MojangSkinLookup.Result(new MojangSkins.Skin(textures, "sig"), null), url -> {
                    throw SkinError.of(SkinError.NETWORK, "textures");
                });
        Wait wait = new Wait();

        signing.submit(new SkinSigning.Request(ME, "借", null, new SkinSigning.Source.Player("Notch")), wait);

        assertEquals(SkinError.NETWORK, wait.get().problem().code());
        assertNull(cache.find(SkinRef.of(PNG, SkinProfile.VARIANT_CLASSIC)));
    }
}
