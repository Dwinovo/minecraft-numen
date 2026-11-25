package com.dwinovo.numen.gametest;

import com.dwinovo.numen.api.entity.CompanionRegistry;
import com.dwinovo.numen.api.entity.MojangSkins;
import com.dwinovo.numen.api.entity.NumenPlayer;
import com.dwinovo.numen.api.home.Home;
import com.dwinovo.numen.api.owner.Libraries;
import com.dwinovo.numen.api.skin.MineSkinClient;
import com.dwinovo.numen.api.skin.MojangSkinLookup;
import com.dwinovo.numen.api.skin.SkinCache;
import com.dwinovo.numen.api.skin.SkinDresser;
import com.dwinovo.numen.api.skin.SkinFolder;
import com.dwinovo.numen.api.skin.SkinProfile;
import com.dwinovo.numen.api.skin.SkinRef;
import com.dwinovo.numen.api.skin.SkinSigning;
import com.dwinovo.numen.api.skin.Wardrobe;
import com.dwinovo.numen.api.store.Io;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.minecraft.InsecurePublicKeyException;
import com.mojang.authlib.minecraft.MinecraftProfileTextures;
import com.mojang.authlib.minecraft.MinecraftSessionService;
import com.mojang.authlib.properties.Property;
import com.mojang.authlib.yggdrasil.ProfileResult;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.security.Signature;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * 她的皮肤从选的人的客户端走到身上:客户端签好(这里换成不联网的假签名器,签名用一把测试密钥)、经库协议的皮肤那一种交给服务端,
 * 服务端用会话服务验签(这里换成认那把测试公钥的)才穿。包的那一跳换成直接调 {@link Libraries#handle}。
 */
public class LibraryCommandGameTests {

    private static final String BORROWED = textures("borrowed");

    /** 一份 Mojang 签名的 textures 数据的样子:里面写着图床的网址,{@code tag} 区分是哪一份。 */
    private static String textures(String tag) {
        return Base64.getEncoder().encodeToString(("{\"textures\":{\"SKIN\":{\"url\":\"http://textures.minecraft.net/" + tag + "\"}}}")
                .getBytes(StandardCharsets.UTF_8));
    }

    /** 测试里扮 Mojang 的一把密钥。 */
    private static KeyPair keyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 用这把私钥签 {@code value}:和 authlib 验的是同一种签名。 */
    private static String sign(KeyPair key, String value) {
        try {
            Signature signature = Signature.getInstance("SHA1withRSA");
            signature.initSign(key.getPrivate());
            signature.update(value.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(signature.sign());
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 原版会话服务验属性签名的样子:只认给定的公钥;没有公钥就什么都验不过。 */
    private static MinecraftSessionService sessionsWith(PublicKey... keys) {
        return new MinecraftSessionService() {
            @Override
            public String getSecurePropertyValue(Property property) {
                if (!property.hasSignature()) {
                    throw new InsecurePublicKeyException.MissingException("Property is not signed");
                }
                for (PublicKey key : keys) {
                    if (property.isSignatureValid(key)) {
                        return property.value();
                    }
                }
                throw new InsecurePublicKeyException.InvalidException("Property has invalid signature");
            }

            @Override
            public void joinServer(UUID profileId, String authenticationToken, String serverId) {
                throw new UnsupportedOperationException();
            }

            @Override
            public ProfileResult hasJoinedServer(String profileName, String serverId, InetAddress address) {
                throw new UnsupportedOperationException();
            }

            @Override
            public Property getPackedTextures(GameProfile profile) {
                throw new UnsupportedOperationException();
            }

            @Override
            public MinecraftProfileTextures unpackTextures(Property packedTextures) {
                throw new UnsupportedOperationException();
            }

            @Override
            public ProfileResult fetchProfile(UUID profileId, boolean requireSecure) {
                throw new UnsupportedOperationException();
            }
        };
    }

    /** 不联网的签名:MineSkin 签出来的 value 是 {@code textures(手臂模型)},借正版玩家的皮肤给 {@link #BORROWED};都用这把测试密钥签。 */
    private static SkinSigning.Backend fakeSigning(KeyPair mojang, AtomicInteger signed) {
        return new SkinSigning.Backend() {
            @Override
            public MineSkinClient.Signed sign(byte[] png, String variant, String apiKey) {
                signed.incrementAndGet();
                String value = textures(variant);
                return new MineSkinClient.Signed(value, LibraryCommandGameTests.sign(mojang, value));
            }

            @Override
            public byte[] fetch(String url) {
                return skinPng(false);
            }

            @Override
            public MojangSkinLookup.Result lookup(String playerName) {
                return new MojangSkinLookup.Result(new MojangSkins.Skin(BORROWED, LibraryCommandGameTests.sign(mojang, BORROWED)),
                        null);
            }

            @Override
            public byte[] texture(String url) {
                return skinPng(true);
            }
        };
    }

    /** 一张真能解码的 64×64 皮肤图(每次造的都不一样);{@code slim} 时细手臂用不到的一块留成透明。 */
    private static byte[] skinPng(boolean slim) {
        java.awt.image.BufferedImage image = new java.awt.image.BufferedImage(64, 64, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 64; y++) {
            for (int x = 0; x < 64; x++) {
                image.setRGB(x, y, 0xFF3A7BD5);
            }
        }
        image.setRGB(0, 0, 0xFF000000 | (int) System.nanoTime());   // 每次不同:不会撞上上一次留在盘上的
        if (slim) {
            image.setRGB(50, 16, 0);
        }
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        try {
            javax.imageio.ImageIO.write(image, "png", out);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        return out.toByteArray();
    }

    /** 皮肤这一种库的存:把签好的交给服务端,答复里没有原因就是收下了。包的那一跳换成直接调。 */
    private static Wardrobe.Dress overTheWire(Libraries libraries, UUID owner) {
        return new Wardrobe.Dress() {
            @Override
            public void wear(UUID companion, SkinRef ref, SkinProfile skin, Consumer<String> answer) {
                JsonObject body = new JsonObject();
                body.addProperty("companion", companion.toString());
                body.addProperty("ref", ref.toString());
                body.addProperty("value", skin.value());
                body.addProperty("signature", skin.signature());
                answer.accept(libraries.handle(owner, false, Libraries.SKIN, Libraries.PUT, body).problem());
            }

            @Override
            public void takeOff(UUID companion, Consumer<String> answer) {
                JsonObject body = new JsonObject();
                body.addProperty("companion", companion.toString());
                answer.accept(libraries.handle(owner, false, Libraries.SKIN, Libraries.DELETE, body).problem());
            }
        };
    }

    /**
     * 服务器皮肤文件夹里的一张,客户端从库协议的皮肤文件那一种拿到原图,按图判断手臂、签一次(不联网的假签名)、交给服务端;服务端验过
     * 测试公钥的签名才穿,穿上后重建的身体挂着签名数据、名册记着引用。同一张再要当场交出去、不再签;借一个正版玩家的皮肤不经 MineSkin;
     * 脱掉走同一个入口。
     */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_clientless")
    public static void a_skin_signed_on_the_choosers_client_is_verified_by_the_server_and_worn(GameTestHelper helper)
            throws Exception {
        var server = helper.getLevel().getServer();
        ServerPlayer owner = GameTestKit.presentPlayer(helper, null, "gametest_dresser");
        var her = com.dwinovo.numen.api.gametest.Bodies.summon(server, owner.getUUID(), "gametest_dressed",
                helper.getLevel(), net.minecraft.world.phys.Vec3.atBottomCenterOf(helper.absolutePos(new BlockPos(8, 2, 8))));
        GameTestKit.occupy(owner, her.getUUID());   // 他的客户端坐着她的座位:她的主人在场,她才留在世界里
        UUID id = her.getUUID();
        var registry = CompanionRegistry.get(server);

        KeyPair mojang = keyPair();
        Libraries libraries = Libraries.of(server, SkinDresser.of(server, sessionsWith(mojang.getPublic())));
        // 客户端那一半:这台电脑的签名缓存与签名队,签名走假的
        Path machine = Files.createTempDirectory("numen-gametest-skins");
        AtomicInteger signed = new AtomicInteger();
        SkinCache cache = new SkinCache(new Home(machine, Io.direct(e -> {
            throw e;
        })));
        SkinSigning signing = new SkinSigning(fakeSigning(mojang, signed), () -> null, server, cache, ms -> { });
        Wardrobe wardrobe = new Wardrobe(signing, cache);
        Wardrobe.Dress dress = overTheWire(libraries, owner.getUUID());

        Path file = SkinFolder.here().dir().resolve("gametest_dress.png");
        Files.createDirectories(file.getParent());
        Files.write(file, skinPng(true));
        com.dwinovo.numen.api.gametest.TestCleanup.whenFinished(helper, () -> {
            signing.close();
            try {
                Files.deleteIfExists(file);
                try (var left = Files.walk(machine)) {
                    left.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
                }
            } catch (java.io.IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        });

        // 客户端向服务端要它的皮肤文件夹:带着原图
        byte[] png = null;
        JsonArray rows = JsonParser.parseString(libraries.view(owner.getUUID(), false, Libraries.SKIN_FILES)).getAsJsonArray();
        for (var row : rows) {
            JsonObject o = row.getAsJsonObject();
            if ("gametest_dress".equals(o.get("name").getAsString())) {
                png = Base64.getDecoder().decode(o.get("png").getAsString());
            }
        }
        helper.assertTrue(png != null, "the server did not list its skin folder: " + rows);
        byte[] image = png;
        SkinRef slim = SkinRef.of(image, SkinProfile.VARIANT_SLIM);
        AtomicReference<SkinSigning.Outcome> first = new AtomicReference<>();
        AtomicReference<SkinSigning.Outcome> borrowed = new AtomicReference<>();

        wardrobe.wear(id, "gametest_dress", new SkinSigning.Source.Image(image), null, dress, new SkinSigning.Listener() {
            @Override
            public void done(SkinSigning.Outcome outcome) {
                first.set(outcome);
            }
        });
        GameTestKit.steps(helper)
                .thenWaitUntil(() -> helper.assertTrue(first.get() != null, "the skin was not signed and handed over"))
                .thenExecute(() -> {
                    helper.assertTrue(first.get().ok(), "the server did not take a skin signed by the right key: "
                            + (first.get().problem() == null ? "" : first.get().problem().getMessage()));
                    var live = NumenPlayer.findByUuid(server, id);
                    String value = textures(SkinProfile.VARIANT_SLIM);
                    helper.assertTrue(value.equals(registry.find(id).skinValue()),
                            "the roster does not hold the signed data (透明的区域说明是细手臂)");
                    helper.assertTrue(live != null && live.getGameProfile().getProperties().containsKey("textures")
                                    && value.equals(live.getGameProfile().getProperties().get("textures").iterator().next().value()),
                            "the body does not carry the signed data");
                    helper.assertTrue(slim.toString().equals(registry.find(id).skinRef()),
                            "the roster does not say which skin she wears: " + registry.find(id).skinRef());
                    helper.assertTrue(signed.get() == 1, "signed " + signed.get() + " times");
                    // 同一张再要:本机缓存里有,不排队、不再签,当场交出去
                    AtomicReference<SkinSigning.Outcome> again = new AtomicReference<>();
                    long ticket = wardrobe.wear(id, "gametest_dress", new SkinSigning.Source.Image(image), null, dress,
                            new SkinSigning.Listener() {
                                @Override
                                public void done(SkinSigning.Outcome outcome) {
                                    again.set(outcome);
                                }
                            });
                    helper.assertTrue(ticket == 0 && again.get() != null && again.get().ok() && signed.get() == 1,
                            "a skin signed before was signed again: " + signed.get());
                    // 借正版玩家的皮肤:Mojang 签好的,不经 MineSkin
                    wardrobe.wear(id, "Notch", new SkinSigning.Source.Player("Notch"), null, dress, new SkinSigning.Listener() {
                        @Override
                        public void done(SkinSigning.Outcome outcome) {
                            borrowed.set(outcome);
                        }
                    });
                })
                .thenWaitUntil(() -> helper.assertTrue(borrowed.get() != null, "the borrowed skin did not arrive"))
                .thenExecute(() -> {
                    helper.assertTrue(borrowed.get().ok() && BORROWED.equals(registry.find(id).skinValue()),
                            "the borrowed skin was not worn");
                    helper.assertTrue(signed.get() == 1, "a borrowed skin was sent to MineSkin");
                    // 改过内容的、没有公钥可验的、不是你的同伴:都不穿
                    JsonObject body = new JsonObject();
                    body.addProperty("companion", id.toString());
                    body.addProperty("ref", slim.toString());
                    body.addProperty("value", textures("forged"));
                    body.addProperty("signature", sign(mojang, BORROWED));
                    helper.assertTrue(libraries.handle(owner.getUUID(), false, Libraries.SKIN, Libraries.PUT, body).problem() != null,
                            "a forged skin was taken");
                    Libraries keyless = Libraries.of(server, SkinDresser.of(server, sessionsWith()));
                    body.addProperty("value", textures(SkinProfile.VARIANT_SLIM));
                    body.addProperty("signature", sign(mojang, textures(SkinProfile.VARIANT_SLIM)));
                    helper.assertTrue(keyless.handle(owner.getUUID(), false, Libraries.SKIN, Libraries.PUT, body).problem() != null,
                            "a skin was taken though no key could check it");
                    helper.assertTrue(libraries.handle(UUID.randomUUID(), false, Libraries.SKIN, Libraries.PUT, body).problem() != null,
                            "someone else's companion was dressed");
                    helper.assertTrue(BORROWED.equals(registry.find(id).skinValue()), "a refused skin changed what she wears");
                    // 脱掉:同一个入口
                    AtomicReference<String> off = new AtomicReference<>("pending");
                    dress.takeOff(id, off::set);
                    helper.assertTrue(off.get() == null && registry.find(id).skinValue().isEmpty()
                            && registry.find(id).skinRef().isEmpty(), "the skin was not taken off: " + off.get());
                    var live = NumenPlayer.findByUuid(server, id);
                    if (live != null) {
                        com.dwinovo.numen.api.entity.CompanionFactory.despawn(server, live);
                    }
                    registry.remove(id);
                    GameTestKit.leave(owner);
                })
                .thenSucceed();
    }
}
