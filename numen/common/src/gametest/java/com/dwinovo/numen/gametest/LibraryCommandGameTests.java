package com.dwinovo.numen.gametest;

import com.dwinovo.numen.api.brain.ModelServices;
import com.dwinovo.numen.api.brain.Replies;
import com.dwinovo.numen.api.brain.SkinSignings;
import com.dwinovo.numen.api.brain.Slot;
import com.dwinovo.numen.api.data.ModLanguageData.Keys;
import com.dwinovo.numen.api.library.EntryId;
import com.dwinovo.numen.api.library.LibraryHub;
import com.dwinovo.numen.api.persona.PersonaLibrary;
import com.dwinovo.numen.api.skin.SkinError;
import com.dwinovo.numen.api.skin.SkinProfile;
import com.dwinovo.numen.api.voice.VoiceProfile;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import static com.dwinovo.numen.gametest.ClientlessGameTests.isKey;
import static com.dwinovo.numen.gametest.ClientlessGameTests.run;

/**
 * 没装客户端的主人用命令管她的绑定、他自己的 key 和皮肤库:{@code /numen companion …}、{@code /numen key …}、
 * {@code /numen skin add …}。主人是 {@link GameTestKit#presentVanillaPlayer}(原版客户端的样子)。
 */
public class LibraryCommandGameTests {

    /** 四项绑定经命令换上、换下,和面板编辑卡走同一个入口;库里没有的条目说清楚、不动她的绑定。 */
    @GameTest(template = "floor16", timeoutTicks = 300, batch = "numen_clientless")
    public static void companion_commands_bind_and_unbind_her_four_items(GameTestHelper helper) throws Exception {
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
        EntryId skin = EntryId.mine("gametest_cmd_skin");
        hub.library(SkinProfile.KIND, owner.getUUID()).mine().write(skin,
                new SkinProfile("classic", "cmd-value", "cmd-sig", null).write());
        List<Component> heard = new ArrayList<>();

        run(owner, "numen companion gametest_bound persona " + persona, heard);
        run(owner, "numen companion gametest_bound voice " + voice, heard);
        run(owner, "numen companion gametest_bound skin " + skin, heard);
        helper.assertTrue(persona.toString().equals(her.loop.bound(Slot.PERSONA)), "persona: " + heard);
        helper.assertTrue(voice.toString().equals(her.loop.bound(Slot.VOICE)), "voice: " + heard);
        helper.assertTrue(skin.toString().equals(her.loop.bound(Slot.SKIN)), "skin: " + heard);
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
        hub.library(SkinProfile.KIND, owner.getUUID()).mine().delete(skin);
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

    /**
     * 往他的皮肤库加一张:名字不合规当场说;网址指向本机的被拒(服务端不替玩家探内网),这一张进过队、以失败收场,
     * 失败的原因留在他看得到的队里——不联网。
     */
    @GameTest(template = "floor16", timeoutTicks = 400, batch = "numen_clientless")
    public static void skin_command_queues_on_the_server_and_refuses_an_address_inside_it(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        ServerPlayer owner = GameTestKit.presentVanillaPlayer(helper, null, "gametest_skinner", "en_us");
        List<Component> heard = new ArrayList<>();

        run(owner, "numen skin add \"a/b\" url http://127.0.0.1/skin.png", heard);
        helper.assertTrue(heard.size() == 1 && isKey(heard.get(0), Keys.COMMAND_SKIN_BAD_NAME), "bad name: " + heard);
        run(owner, "numen skin add \"本机\" url http://127.0.0.1/skin.png", heard);

        GameTestKit.succeedWhen(helper, () -> {
            var jobs = SkinSignings.of(server).pendingOf(owner.getUUID());
            helper.assertTrue(jobs.size() == 1 && jobs.get(0).failure() != null
                            && SkinError.BAD_IMAGE.equals(jobs.get(0).failure().code()),
                    "the loopback address was not refused in the queue: " + jobs);
            helper.assertTrue(LibraryHub.of(server).library(SkinProfile.KIND, owner.getUUID()).active().isEmpty(),
                    "a skin that failed signing became an entry");
            SkinSignings.of(server).cancel(owner.getUUID(), jobs.get(0).id());
            GameTestKit.leave(owner);
        });
    }
}
