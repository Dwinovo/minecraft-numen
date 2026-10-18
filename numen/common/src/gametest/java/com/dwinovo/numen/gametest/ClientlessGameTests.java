package com.dwinovo.numen.gametest;

import com.dwinovo.numen.api.agent.inbox.EventTypes;
import com.dwinovo.numen.api.brain.Replies;
import com.dwinovo.numen.api.data.ModLanguageData.Keys;
import com.dwinovo.numen.api.entity.Companions;
import com.dwinovo.numen.api.entity.NumenPlayer;
import com.dwinovo.numen.api.gametest.OwnerLine;
import com.dwinovo.numen.api.network.NumenNetwork;
import com.dwinovo.numen.api.network.payload.ClientUiActionPayload;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.ChatType;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.OutgoingChatMessage;
import net.minecraft.network.chat.PlayerChatMessage;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.level.ServerPlayer;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import static com.dwinovo.numen.gametest.GameTestKit.succeedWhen;

/**
 * 没装我们的客户端的主人(原版客户端):服务端不往他的连接上发我们的包,他能用的原版手段(私聊、指令、可点的聊天消息)见本类其余用例。
 * 玩家是 {@link GameTestKit#presentVanillaPlayer}:连接上没有我们的频道,下行的聊天记在连接上。
 */
public class ClientlessGameTests {

    /** 这位玩家敲一条指令,指令回给他的话收进 {@code heard}(在送上线之前,还是带着语言键的 Component)。 */
    static void run(ServerPlayer player, String command, List<Component> heard) {
        CommandSourceStack source = player.createCommandSourceStack().withSource(new CommandSource() {
            @Override
            public void sendSystemMessage(Component message) {
                heard.add(message);
            }

            @Override
            public boolean acceptsSuccess() {
                return true;
            }

            @Override
            public boolean acceptsFailure() {
                return true;
            }

            @Override
            public boolean shouldInformAdmins() {
                return false;
            }
        });
        player.getServer().getCommands().performPrefixedCommand(source, command);
    }

    /** 这句话(指令的失败回话外面还包着一层红色的壳)里有没有这个语言键。 */
    static boolean isKey(Component message, String key) {
        return message.getContents() instanceof TranslatableContents t && t.getKey().equals(key)
                || message.getSiblings().stream().anyMatch(sibling -> isKey(sibling, key));
    }

    /** 可选频道:发给没有频道的连接,什么也不发、也不抛(NeoForge 在发送处就会抛);作用在他客户端上的指令说一声。 */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_clientless")
    public static void a_player_without_our_client_is_sent_none_of_our_packets(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        ServerPlayer plain = GameTestKit.presentVanillaPlayer(helper, null, "gametest_plain", "en_us");

        Companions.syncRosterToOwner(server, plain);
        NumenNetwork.sendToPlayer(plain, new ClientUiActionPayload(ClientUiActionPayload.Action.DEBUG_TEXT_ON));
        helper.assertTrue(GameTestKit.received(plain).isEmpty(),
                "our packets were sent to a connection that has no channel for them");

        List<Component> heard = new ArrayList<>();
        run(plain, "numen settings", heard);
        helper.assertTrue(heard.size() == 1 && isKey(heard.get(0), Keys.COMMAND_NEEDS_CLIENT),
                "a command that acts on his client did not say he has none: " + heard);

        GameTestKit.leave(plain);
        helper.succeed();
    }

    /**
     * 没装客户端的玩家收到的是翻好的字:按他客户端报上来的语言,语言表读自 Numen 的 jar(客户端用的是同一份)。认不得的语言
     * 退回英文,和客户端自己的规矩一样。
     */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_clientless")
    public static void a_player_without_our_client_is_told_in_the_language_his_client_reports(GameTestHelper helper) {
        ServerPlayer chinese = GameTestKit.presentVanillaPlayer(helper, null, "gametest_zh", "zh_cn");
        ServerPlayer english = GameTestKit.presentVanillaPlayer(helper, null, "gametest_en", "en_us");
        ServerPlayer elsewhere = GameTestKit.presentVanillaPlayer(helper, null, "gametest_xx", "xx_xx");
        List<ServerPlayer> players = List.of(chinese, english, elsewhere);
        for (ServerPlayer player : players) {
            GameTestKit.said(player);
            player.getServer().getCommands().performPrefixedCommand(player.createCommandSourceStack(), "numen settings");
        }

        helper.assertTrue(GameTestKit.said(chinese).stream().anyMatch(s -> s.text().getString().contains("要装了 Numen 客户端")),
                "the Chinese client was not told in Chinese");
        for (ServerPlayer player : List.of(english, elsewhere)) {
            List<OwnerLine.Said> said = GameTestKit.said(player);
            helper.assertTrue(said.stream().anyMatch(s -> s.text().getString().contains("This needs the Numen client")),
                    "a client with no matching language was not told in English: " + said);
        }
        players.forEach(GameTestKit::leave);
        helper.succeed();
    }

    /**
     * 原版 {@code /msg} 把话送到收话人手里的最后一步(它的 {@code MsgCommand.sendMessage} 是私有的,所以照它的写法重做):
     * 签了名的私聊带着发话人的 uuid,聊天类型是 {@code msg_command_incoming}。经指令的字符串走会被当成无来源的系统私聊,
     * 真客户端发的签名指令不会。
     */
    private static void whisper(ServerPlayer from, NumenPlayer to, String text) {
        to.sendChatMessage(OutgoingChatMessage.create(PlayerChatMessage.unsigned(from.getUUID(), text)), false,
                ChatType.bind(ChatType.MSG_COMMAND_INCOMING, from.createCommandSourceStack()));
    }

    /** 主人私聊她:话进她的单聊会话,她回的话以私信的样子回到他手里。 */
    @GameTest(template = "floor16", timeoutTicks = 300, batch = "numen_clientless")
    public static void the_owners_whisper_reaches_her_and_she_whispers_back(GameTestHelper helper) throws Exception {
        var server = helper.getLevel().getServer();
        ServerPlayer owner = GameTestKit.presentVanillaPlayer(helper, null, "gametest_whisperer", "en_us");
        BrainGameTests.Brain her = new BrainGameTests.Brain(helper, "gametest_whispered",
                Files.createTempDirectory("numen-gametest-brain-"), owner.getUUID(),
                Replies.link(server, owner.getUUID()));
        her.mind.say("在呢");

        whisper(owner, her.companion, "你好");

        List<OwnerLine.Said> heard = new ArrayList<>();
        succeedWhen(helper, () -> {
            heard.addAll(GameTestKit.said(owner));
            helper.assertTrue(her.conversation().stream().anyMatch(m -> m instanceof
                            com.dwinovo.numen.api.agent.llm.ConvoState.Msg.User u && u.content().contains("你好")),
                    "the whisper is not in her log: " + her.conversation());
            helper.assertTrue(heard.stream().anyMatch(said -> said.text().getContents() instanceof TranslatableContents t
                            && t.getKey().equals("commands.message.display.incoming")
                            && said.text().getString().contains("在呢") && !said.overlay()),
                    "her answer did not come back as a whisper: " + heard);
            her.close();
            GameTestKit.leave(owner);
        });
    }

    /** 别的玩家私聊她:按旁听档进收件箱、标着是谁说的,不叫醒她。 */
    @GameTest(template = "floor16", timeoutTicks = 300, batch = "numen_clientless")
    public static void a_strangers_whisper_is_overheard_and_does_not_wake_her(GameTestHelper helper) throws Exception {
        var server = helper.getLevel().getServer();
        ServerPlayer owner = GameTestKit.presentVanillaPlayer(helper, null, "gametest_host", "en_us");
        ServerPlayer stranger = GameTestKit.presentVanillaPlayer(helper, null, "gametest_passerby", "en_us");
        BrainGameTests.Brain her = new BrainGameTests.Brain(helper, "gametest_overhearer",
                Files.createTempDirectory("numen-gametest-brain-"), owner.getUUID(),
                Replies.link(server, owner.getUUID()));

        whisper(stranger, her.companion, "借把镐子");

        helper.assertTrue(her.loop.inbox().entries().stream().anyMatch(e -> e.type().equals(EventTypes.TALK)
                        && e.text().contains("gametest_passerby → gametest_overhearer") && e.text().contains("借把镐子")),
                "the stranger's whisper is not in her inbox, marked with who said it: " + her.loop.inbox().entries());
        helper.runAfterDelay(20, () -> {
            helper.assertTrue(her.mind.asked.isEmpty(), "a stranger's whisper woke her");
            her.close();
            GameTestKit.leave(owner);
            GameTestKit.leave(stranger);
            helper.succeed();
        });
    }
}
