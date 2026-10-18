package com.dwinovo.numen.gametest;

import com.dwinovo.numen.api.agent.conversation.Conversation;
import com.dwinovo.numen.api.brain.Conversations;
import com.dwinovo.numen.api.brain.Replies;
import com.dwinovo.numen.api.data.ModLanguageData.Keys;
import com.dwinovo.numen.api.gametest.OwnerLine;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.LastSeenMessages;
import net.minecraft.network.protocol.game.ServerboundChatPacket;
import net.minecraft.server.level.ServerPlayer;

import java.nio.file.Files;
import java.time.Instant;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;

import static com.dwinovo.numen.gametest.ClientlessGameTests.isKey;
import static com.dwinovo.numen.gametest.ClientlessGameTests.run;

/**
 * 没装客户端的主人用命令操作会话、在聊天栏里跟会话说话:{@code /numen group …} 与 {@code /numen chat …}。主人是
 * {@link GameTestKit#presentVanillaPlayer}(原版客户端的样子),聊天栏里打字走真的聊天包入口。
 */
public class ConversationCommandGameTests {

    /** 一位没装客户端的主人名下的两只同伴(同一个主人,各有各的脑)。 */
    private record Pair(ServerPlayer owner, BrainGameTests.Brain a, BrainGameTests.Brain b) {

        static Pair of(GameTestHelper helper, String ownerName, String aName, String bName) throws Exception {
            var server = helper.getLevel().getServer();
            ServerPlayer owner = GameTestKit.presentVanillaPlayer(helper, null, ownerName, "en_us");
            var link = Replies.link(server, owner.getUUID());
            var a = new BrainGameTests.Brain(helper, aName, Files.createTempDirectory("numen-gametest-brain-"),
                    owner.getUUID(), link);
            var b = new BrainGameTests.Brain(helper, bName, Files.createTempDirectory("numen-gametest-brain-"),
                    owner.getUUID(), link);
            return new Pair(owner, a, b);
        }

        void close() {
            a.close();
            b.close();
            GameTestKit.leave(owner);
        }
    }

    /** 命令把成员表拉人、移人、改名、解散,和面板上的操作落在同一张表上;会话照名字或 id 指到。 */
    @GameTest(template = "floor16", timeoutTicks = 300, batch = "numen_clientless")
    public static void group_commands_arrange_the_members_table_like_the_panel(GameTestHelper helper)
            throws Exception {
        Pair pair = Pair.of(helper, "gametest_organizer", "gametest_ga", "gametest_gb");
        Conversations rooms = Conversations.of(helper.getLevel().getServer(), pair.owner().getUUID());
        List<Component> heard = new ArrayList<>();

        run(pair.owner(), "numen group invite gametest_ga gametest_gb", heard);
        helper.assertTrue(rooms.list().size() == 1 && rooms.list().get(0).members()
                        .equals(List.of(pair.a().companion.getUUID(), pair.b().companion.getUUID())),
                "inviting her into a conversation did not make the group: " + rooms.list() + " " + heard);
        Conversation group = rooms.list().get(0);

        run(pair.owner(), "numen group rename " + group.id() + " 茶话会", heard);
        helper.assertTrue("茶话会".equals(rooms.get(group.id()).name()), "the group was not renamed: " + rooms.list());

        run(pair.owner(), "numen group remove \"茶话会\" gametest_gb", heard);
        helper.assertTrue(rooms.get(group.id()).members().equals(List.of(pair.a().companion.getUUID())),
                "removing her did not take her out: " + rooms.list());

        run(pair.owner(), "numen group dissolve \"茶话会\"", heard);
        helper.assertTrue(rooms.get(group.id()) == null, "the group was not dissolved: " + rooms.list());

        heard.clear();
        run(pair.owner(), "numen group dissolve \"没有这个会话\"", heard);
        helper.assertTrue(heard.size() == 1 && isKey(heard.get(0), Keys.COMMAND_GROUP_NO_CONVERSATION),
                "a name that is no conversation was not said so: " + heard);
        pair.close();
        helper.succeed();
    }

    private static void typeInChatBar(ServerPlayer player, String line) {
        player.connection.handleChat(new ServerboundChatPacket(line, Instant.now(), 0L, null,
                new LastSeenMessages.Update(0, new BitSet())));
    }

    /**
     * 聊天栏切进会话:打的字进会话、公屏上别人看不到;@ 谁谁醒、别的旁听;她回的话回在带会话名的聊天行;补全词是成员的名字、
     * 成员变了跟着换;切回公屏以后打的字照常广播。
     */
    @GameTest(template = "floor16", timeoutTicks = 600, batch = "numen_clientless")
    public static void a_chat_bar_routed_into_a_conversation_speaks_there_and_not_in_public(GameTestHelper helper)
            throws Exception {
        Pair pair = Pair.of(helper, "gametest_chatter", "gametest_ca", "gametest_cb");
        ServerPlayer bystander = GameTestKit.presentVanillaPlayer(helper, null, "gametest_bystander", "en_us");
        Conversations rooms = Conversations.of(helper.getLevel().getServer(), pair.owner().getUUID());
        List<Component> heard = new ArrayList<>();
        pair.a().mind.say("来了");
        run(pair.owner(), "numen group invite gametest_ca gametest_cb", heard);
        Conversation group = rooms.list().get(0);
        run(pair.owner(), "numen group rename " + group.id() + " 茶话会", heard);
        GameTestKit.completions(pair.owner());

        run(pair.owner(), "numen chat \"茶话会\"", heard);
        var set = GameTestKit.completions(pair.owner());
        helper.assertTrue(set.size() == 1 && set.get(0).entries().equals(List.of("@gametest_ca", "@gametest_cb")),
                "the @ completions are not the members' names: " + set);
        typeInChatBar(pair.owner(), "@gametest_ca 过来一下");

        List<OwnerLine.Said> seen = new ArrayList<>();
        helper.runAfterDelay(20, () -> {
            seen.addAll(GameTestKit.said(pair.owner()));
            helper.assertTrue(pair.a().mind.asked.size() == 1, "the one called did not wake");
            helper.assertTrue(pair.b().mind.asked.isEmpty(), "the one who only overheard woke up");
            helper.assertTrue(GameTestKit.publicChat(bystander).isEmpty(), "the routed line was broadcast to the room");
            helper.assertTrue(seen.stream().anyMatch(l ->
                            l.text().getString().equals("[茶话会] <gametest_chatter> @gametest_ca 过来一下")),
                    "the line was not echoed to him under the conversation's name: " + seen);
            helper.assertTrue(seen.stream().anyMatch(l -> l.text().getString().equals("[茶话会] <gametest_ca> 来了")),
                    "her answer did not come back under the conversation's name: " + seen);

            run(pair.owner(), "numen group remove \"茶话会\" gametest_cb", heard);
            var changed = GameTestKit.completions(pair.owner());
            helper.assertTrue(changed.size() == 1 && changed.get(0).entries().equals(List.of("@gametest_ca")),
                    "the completions did not follow the members: " + changed);

            run(pair.owner(), "numen chat", heard);
            helper.assertTrue(GameTestKit.completions(pair.owner()).get(0).entries().isEmpty(),
                    "leaving the conversation left its completions behind");
            typeInChatBar(pair.owner(), "大家好");
            helper.runAfterDelay(20, () -> {
                helper.assertTrue(GameTestKit.publicChat(bystander).contains("大家好"),
                        "after leaving, his line did not go to public chat");
                pair.close();
                GameTestKit.leave(bystander);
                helper.succeed();
            });
        });
    }

    /** {@code /numen chat <会话> <话>} 只对会话说这一句,聊天栏不切进去。 */
    @GameTest(template = "floor16", timeoutTicks = 300, batch = "numen_clientless")
    public static void one_line_to_a_conversation_does_not_route_the_chat_bar(GameTestHelper helper) throws Exception {
        Pair pair = Pair.of(helper, "gametest_sayer", "gametest_sa", "gametest_sb");
        Conversations rooms = Conversations.of(helper.getLevel().getServer(), pair.owner().getUUID());
        List<Component> heard = new ArrayList<>();
        pair.b().mind.say("听到了");

        run(pair.owner(), "numen chat gametest_sb @gametest_sb 你在吗", heard);

        helper.assertTrue(com.dwinovo.numen.api.brain.ChatRoutes.routeOf(pair.owner().getUUID()) == null,
                "a one-off line routed his chat bar");
        helper.runAfterDelay(20, () -> {
            helper.assertTrue(pair.b().mind.asked.size() == 1 && pair.a().mind.asked.isEmpty(),
                    "the line did not reach exactly the one it named");
            helper.assertTrue(rooms.list().isEmpty(), "a one-off line made a saved conversation");
            pair.close();
            helper.succeed();
        });
    }
}
