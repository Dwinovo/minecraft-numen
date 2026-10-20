package com.dwinovo.numen.gametest;

import com.dwinovo.numen.api.brain.CompanionLoops;
import com.dwinovo.numen.api.brain.ModelServices;
import com.dwinovo.numen.api.brain.Replies;
import com.dwinovo.numen.api.brain.ServerSkins;
import com.dwinovo.numen.api.brain.Slot;
import com.dwinovo.numen.api.data.ModLanguageData.Keys;
import com.dwinovo.numen.api.library.EntryId;
import com.dwinovo.numen.api.library.LibraryHub;
import com.dwinovo.numen.api.persona.PersonaLibrary;
import com.dwinovo.numen.api.skin.MineSkinClient;
import com.dwinovo.numen.api.skin.MojangSkinLookup;
import com.dwinovo.numen.api.skin.SkinError;
import com.dwinovo.numen.api.skin.SkinFolder;
import com.dwinovo.numen.api.skin.SkinSigning;
import com.dwinovo.numen.api.voice.VoiceProfile;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static com.dwinovo.numen.gametest.ClientlessGameTests.isKey;
import static com.dwinovo.numen.gametest.ClientlessGameTests.run;

/**
 * 没装客户端的主人用命令管她的绑定、她的皮肤、他自己的 key:{@code /numen companion …}、{@code /numen companion … skin …}、
 * {@code /numen key …}。主人是 {@link GameTestKit#presentVanillaPlayer}(原版客户端的样子)。
 */
public class LibraryCommandGameTests {

    /** 三项绑定经命令换上、换下,和面板编辑卡走同一个入口;库里没有的条目说清楚、不动她的绑定。 */
    @GameTest(template = "floor16", timeoutTicks = 300, batch = "numen_clientless")
    public static void companion_commands_bind_and_unbind_her_three_items(GameTestHelper helper) throws Exception {
        var server = helper.getLevel().getServer();
        ServerPlayer owner = GameTestKit.presentVanillaPlayer(helper, null, "gametest_binder", "en_us");
        var her = new BrainGameTests.Brain(helper, "gametest_bound", Files.createTempDirectory("numen-gametest-brain-"),
                owner.getUUID(), Replies.link(server, owner.getUUID()));
        var hub = LibraryHub.of(server);
        EntryId persona = EntryId.mine("gametest_cmd_persona");
        hub.library(PersonaLibrary.KIND, owner.getUUID()).mine().write(persona, "你是测试员\n");
        EntryId voice = EntryId.mine("gametest_cmd_voice");
        hub.library(VoiceProfile.KIND, owner.getUUID()).mine().write(voice,
                new VoiceProfile("", "openai", "", "", "m", "alex", "", "", "", 1f).write());
        List<Component> heard = new ArrayList<>();

        run(owner, "numen companion gametest_bound persona " + persona, heard);
        run(owner, "numen companion gametest_bound voice " + voice, heard);
        helper.assertTrue(persona.toString().equals(her.loop.bound(Slot.PERSONA)), "persona: " + heard);
        helper.assertTrue(voice.toString().equals(her.loop.bound(Slot.VOICE)), "voice: " + heard);
        helper.assertTrue(heard.stream().allMatch(m -> isKey(m, Keys.COMMAND_BOUND)), "not told: " + heard);

        heard.clear();
        run(owner, "numen companion gametest_bound voice my:not_there", heard);
        helper.assertTrue(heard.size() == 1 && isKey(heard.get(0), Keys.COMMAND_BIND_FAILED), "refusal not said: " + heard);
        helper.assertTrue(voice.toString().equals(her.loop.bound(Slot.VOICE)), "a refused bind changed her voice");

        heard.clear();
        run(owner, "numen companion gametest_bound voice", heard);
        helper.assertTrue(her.loop.bound(Slot.VOICE) == null && isKey(heard.get(0), Keys.COMMAND_UNBOUND),
                "the voice was not taken off: " + heard);
        run(owner, "numen companion gametest_bound profile", heard);
        helper.assertTrue(her.loop.bound(Slot.PROVIDER) == null, "a profile cannot be unbound by leaving it out");

        hub.library(PersonaLibrary.KIND, owner.getUUID()).mine().delete(persona);
        hub.library(VoiceProfile.KIND, owner.getUUID()).mine().delete(voice);
        her.close();
        GameTestKit.leave(owner);
        helper.succeed();
    }

    /** 自己的 key 存进他自己的钥匙串;回执只露前后几位、不带全文;多人服第一次存提醒一次。 */
    @GameTest(template = "floor16", timeoutTicks = 300, batch = "numen_clientless")
    public static void key_command_stores_the_key_and_only_shows_its_ends(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        ServerPlayer owner = GameTestKit.presentVanillaPlayer(helper, null, "gametest_keeper", "en_us");
        String key = "sk-abcdefghijklmnop";
        List<Component> heard = new ArrayList<>();

        run(owner, "numen key deepseek " + key, heard);

        helper.assertTrue(key.equals(ModelServices.of(server).ownerKeychain(owner.getUUID()).get("deepseek")),
                "the key was not stored: " + heard);
        helper.assertTrue(heard.stream().anyMatch(m -> isKey(m, Keys.COMMAND_KEY_SAVED)), "not told: " + heard);
        helper.assertTrue(heard.stream().noneMatch(m -> m.getString().contains(key) || contents(m).contains(key)),
                "the whole key was echoed: " + heard);
        helper.assertTrue(heard.stream().anyMatch(m -> contents(m).contains("sk-…op")), "the ends were not shown: " + heard);
        helper.assertTrue(heard.stream().anyMatch(m -> isKey(m, Keys.KEY_OWNER_SEES)),
                "the first save on a multiplayer server was not reminded: " + heard);

        heard.clear();
        run(owner, "numen key deepseek " + key + "2", heard);
        helper.assertTrue(heard.stream().noneMatch(m -> isKey(m, Keys.KEY_OWNER_SEES)), "reminded twice: " + heard);

        ModelServices.of(server).ownerKeychain(owner.getUUID()).put("deepseek", "");
        GameTestKit.leave(owner);
        helper.succeed();
    }

    private static String contents(Component message) {
        StringBuilder out = new StringBuilder();
        message.visit(piece -> {
            out.append(piece);
            return java.util.Optional.empty();
        });
        if (message.getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents t) {
            for (Object arg : t.getArgs()) {
                out.append(arg instanceof Component c ? contents(c) : String.valueOf(arg));
            }
        }
        return out.toString();
    }

    /** 不联网的签名:MineSkin 签出来的 value 是 {@code v-手臂模型},借正版玩家的皮肤给 {@link #BORROWED}。 */
    private static final String BORROWED = java.util.Base64.getEncoder().encodeToString(
            "{\"textures\":{\"SKIN\":{\"url\":\"http://textures.minecraft.net/gametest\"}}}"
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));

    private static SkinSigning.Backend fakeSigning(java.util.concurrent.atomic.AtomicInteger signed) {
        return new SkinSigning.Backend() {
            @Override
            public MineSkinClient.Signed sign(byte[] png, String variant, String apiKey) {
                signed.incrementAndGet();
                return new MineSkinClient.Signed("v-" + variant, "gametest-sig");
            }

            @Override
            public byte[] fetch(String url) {
                return skinPng(false);
            }

            @Override
            public MojangSkinLookup.Result lookup(String playerName) {
                return new MojangSkinLookup.Result(new com.dwinovo.numen.api.entity.MojangSkins.Skin(BORROWED, "mojang-sig"),
                        null);
            }

            @Override
            public byte[] texture(String url) {
                return skinPng(true);
            }
        };
    }

    /** 一张真能解码的 64×64 皮肤图;{@code slim} 时细手臂用不到的一块留成透明。 */
    private static byte[] skinPng(boolean slim) {
        java.awt.image.BufferedImage image = new java.awt.image.BufferedImage(64, 64, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 64; y++) {
            for (int x = 0; x < 64; x++) {
                image.setRGB(x, y, 0xFF3A7BD5);
            }
        }
        if (slim) {
            image.setRGB(50, 16, 0);
        }
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        try {
            javax.imageio.ImageIO.write(image, "png", out);
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
        return out.toByteArray();
    }

    /**
     * 她的皮肤经命令换:服务器皮肤文件夹里的一张按图判断手臂、签一次(签名用不联网的假签名器),穿上后重建的身体挂着缓存里的签名数据;
     * 同一张再要当场穿上、不再签;借一个正版玩家的皮肤不经 MineSkin;不写参数是脱掉。
     */
    @GameTest(template = "floor16", timeoutTicks = 600, batch = "numen_clientless")
    public static void skin_commands_dress_her_from_the_servers_folder_or_a_player_and_undress_her(GameTestHelper helper)
            throws Exception {
        var server = helper.getLevel().getServer();
        ServerPlayer owner = GameTestKit.presentVanillaPlayer(helper, null, "gametest_dresser", "en_us");
        var her = new BrainGameTests.Brain(helper, "gametest_dressed", Files.createTempDirectory("numen-gametest-brain-"),
                owner.getUUID(), Replies.link(server, owner.getUUID()));
        java.util.concurrent.atomic.AtomicInteger signed = new java.util.concurrent.atomic.AtomicInteger();
        ServerSkins.start(server, fakeSigning(signed));
        java.nio.file.Path file = SkinFolder.here().dir().resolve("gametest_dress.png");
        Files.createDirectories(file.getParent());
        Files.write(file, skinPng(true));
        var registry = com.dwinovo.numen.api.entity.CompanionRegistry.get(server);
        UUID id = her.companion.getUUID();
        List<Component> heard = new ArrayList<>();

        run(owner, "numen companion gametest_dressed skin server gametest_dress", heard);
        GameTestKit.steps(helper)
                .thenWaitUntil(() -> helper.assertTrue("v-slim".equals(registry.find(id).skinValue()),
                        "she does not wear the signed skin (透明的区域说明是细手臂): " + heard))
                .thenExecute(() -> {
                    var live = com.dwinovo.numen.api.entity.NumenPlayer.findByUuid(server, id);
                    helper.assertTrue(live != null && live.getGameProfile().getProperties().containsKey("textures")
                                    && "v-slim".equals(live.getGameProfile().getProperties().get("textures")
                                    .iterator().next().value()),
                            "the rebuilt body does not carry the cached signature");
                    helper.assertTrue(GameTestKit.said(owner).stream().anyMatch(m -> m.text().getString().contains("now wears skin")),
                            "the owner was not told it was worn");
                    helper.assertTrue(CompanionLoops.get(id).orElseThrow().state().skinId() != null,
                            "her binding does not say which skin she wears");
                    heard.clear();
                    run(owner, "numen companion gametest_dressed skin server gametest_dress", heard);
                    helper.assertTrue(GameTestKit.said(owner).stream().anyMatch(m -> m.text().getString().contains("now wears skin")),
                            "a skin signed before was not worn at once");
                    helper.assertTrue(signed.get() == 1, "the same image was signed again: " + signed.get());
                    heard.clear();
                    run(owner, "numen companion gametest_dressed skin player Notch", heard);
                })
                .thenWaitUntil(() -> helper.assertTrue(BORROWED.equals(registry.find(id).skinValue()),
                        "the borrowed skin did not arrive: " + heard))
                .thenExecute(() -> {
                    helper.assertTrue(signed.get() == 1, "a borrowed skin was sent to MineSkin");
                    heard.clear();
                    run(owner, "numen companion gametest_dressed skin", heard);
                    helper.assertTrue(registry.find(id).skinValue().isEmpty()
                                    && CompanionLoops.get(id).orElseThrow().state().skinId() == null,
                            "the skin was not taken off: " + heard);
                    run(owner, "numen companion gametest_dressed skin server no_such_skin", heard);
                    helper.assertTrue(heard.stream().anyMatch(m -> isKey(m, Keys.COMMAND_SKIN_FAILED)),
                            "a missing file was not said: " + heard);
                    try {
                        Files.deleteIfExists(file);
                    } catch (java.io.IOException e) {
                        throw new IllegalStateException(e);
                    }
                    ServerSkins.start(server, SkinSigning.Backend.NETWORK);
                    CompanionLoops.dismissed(server, id);
                    var live = com.dwinovo.numen.api.entity.NumenPlayer.findByUuid(server, id);
                    if (live != null) {
                        com.dwinovo.numen.api.entity.CompanionFactory.despawn(server, live);
                    }
                    registry.remove(id);
                    GameTestKit.leave(owner);
                })
                .thenSucceed();
    }
}
