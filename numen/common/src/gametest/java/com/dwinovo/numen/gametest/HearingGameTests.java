package com.dwinovo.numen.gametest;

import com.dwinovo.numen.api.agent.llm.ConvoState;
import com.dwinovo.numen.api.agent.inbox.EventTypes;
import com.dwinovo.numen.api.brain.ChatBar;
import com.dwinovo.numen.api.brain.Conversations;
import com.dwinovo.numen.api.brain.OwnerInput;
import com.dwinovo.numen.api.brain.OwnerInputs;
import com.dwinovo.numen.api.brain.Sharing;
import com.dwinovo.numen.api.entity.Companions;
import com.dwinovo.numen.api.entity.NumenPlayer;
import com.dwinovo.numen.api.gametest.Bodies;
import com.dwinovo.numen.api.gametest.GameBrain;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.BeforeBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.ChatType;
import net.minecraft.network.chat.OutgoingChatMessage;
import net.minecraft.network.chat.PlayerChatMessage;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.UUID;

import static com.dwinovo.numen.gametest.GameTestKit.succeedWhen;

/**
 * 别人的话怎么到她的座位、她的回话怎么回去:私聊她的 keeper、群里点名与旁听、聊天栏切进会话,都由服务端按会话成员表送到创建者的座位
 * (创建者的大脑是 {@link GameBrain});她按说话的地方回话,服务端在那里说出来;她的程序替说话的那位 keeper 做事。
 */
public class HearingGameTests {

    @BeforeBatch(batch = "numen_hearing")
    public static void prepareBatch(ServerLevel level) {
        GameTestKit.settleWorld(level, Difficulty.PEACEFUL, GameTestKit.NOON);
    }

    private static NumenPlayer companion(GameTestHelper helper, UUID owner, String name, int x) {
        return Bodies.summon(helper.getLevel().getServer(), owner, name, helper.getLevel(),
                Vec3.atBottomCenterOf(helper.absolutePos(new BlockPos(x, 2, 8))));
    }

    /** 原版 {@code /msg} 把话送到收话人手里的最后一步(它的 {@code MsgCommand.sendMessage} 是私有的,所以照它的写法重做)。 */
    private static void whisper(ServerPlayer from, NumenPlayer to, String text) {
        to.sendChatMessage(OutgoingChatMessage.create(PlayerChatMessage.unsigned(from.getUUID(), text)), false,
                ChatType.bind(ChatType.MSG_COMMAND_INCOMING, from.createCommandSourceStack()));
    }

    private static boolean sawQuery(BrainGameTests.Scripted mind, String... fragments) {
        return mind.asked.stream().flatMap(r -> r.messages().stream()).anyMatch(m -> {
            if (!(m instanceof ConvoState.Msg.User u)) {
                return false;
            }
            for (String f : fragments) {
                if (!u.content().contains(f)) {
                    return false;
                }
            }
            return true;
        });
    }

    /**
     * 她的 keeper(分享来的朋友)私聊她:话以他的身份({@code player@短码 (名字)})和私聊的地方到她的座位、叫醒她;她的回话以私信回给他
     * (不是创建者);她接着跑的程序替他做事——权限与归属按他,服务端从不自己猜最后是谁说的。
     */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_hearing")
    public static void a_keepers_whisper_wakes_her_and_the_answer_whispers_back_to_him(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        ServerPlayer creator = GameTestKit.presentPlayer(helper, null, "gt_hear_creator");
        ServerPlayer friend = GameTestKit.presentVanillaPlayer(helper, null, "gt_hear_friend", "en_us");
        NumenPlayer her = companion(helper, creator.getUUID(), "gt_hear_her", 8);
        helper.assertTrue(Sharing.share(server, creator.getUUID(), her.getUUID(), friend.getUUID()) == Sharing.Outcome.DONE,
                "she was not shared with the friend");
        BrainGameTests.Scripted mind = new BrainGameTests.Scripted().program("return 1 + 1").say("在的");
        GameBrain brain = GameBrain.sit(server, her, mind);
        GameTestKit.said(friend);
        List<String> toFriend = new java.util.ArrayList<>();
        List<String> toCreator = new java.util.ArrayList<>();
        boolean[] spoken = {false};

        succeedWhen(helper, () -> {
            helper.assertTrue(brain.ready(), "her brain has not taken the seat");
            if (!spoken[0]) {
                whisper(friend, her, "你好");
                spoken[0] = true;
            }
            helper.assertTrue(sawQuery(mind, "你好", "from=\"player@", "(gt_hear_friend)", "venue=\"whisper\""),
                    "her request does not carry the friend's words with who and where: " + mind.asked);
            helper.assertTrue(NumenPlayer.findByUuid(server, her.getUUID()).keeperUuid().equals(friend.getUUID()),
                    "her program did not act for the friend: " + her.keeperUuid() + " (friend " + friend.getUUID()
                            + ", creator " + creator.getUUID() + ", spoken " + brain.host().spoken() + ")");
            GameTestKit.said(friend).forEach(s -> toFriend.add(s.text().getString()));
            GameTestKit.said(creator).forEach(s -> toCreator.add(s.text().getString()));
            helper.assertTrue(toFriend.stream().anyMatch(s -> s.contains("在的")),
                    "the friend did not get her answer as a whisper: " + toFriend);
            helper.assertTrue(toCreator.stream().noneMatch(s -> s.contains("在的")),
                    "the creator was told what she said to the friend: " + toCreator);
            brain.close();
            Companions.dismiss(server, creator.getUUID(), List.of(her.getUUID()));
            GameTestKit.leave(friend);
            GameTestKit.leave(creator);
        });
    }

    /** 不是她的 keeper 的玩家私聊她:是旁听,标着是谁说的,不叫醒她。 */
    @GameTest(template = "floor16", timeoutTicks = 100, batch = "numen_hearing")
    public static void a_strangers_whisper_is_overheard_and_does_not_wake_her(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        ServerPlayer creator = GameTestKit.presentPlayer(helper, null, "gt_over_creator");
        ServerPlayer stranger = GameTestKit.presentVanillaPlayer(helper, null, "gt_over_stranger", "en_us");
        NumenPlayer her = companion(helper, creator.getUUID(), "gt_over_her", 8);
        BrainGameTests.Scripted mind = new BrainGameTests.Scripted().say("不该答");
        GameBrain brain = GameBrain.sit(server, her, mind);
        boolean[] spoken = {false};

        succeedWhen(helper, () -> {
            helper.assertTrue(brain.ready(), "her brain has not taken the seat");
            if (!spoken[0]) {
                whisper(stranger, her, "喂");
                spoken[0] = true;
            }
            helper.assertTrue(brain.host().inbox().entries().stream().anyMatch(e -> e.type().equals(EventTypes.TALK)
                            && e.text().contains("喂") && e.text().contains("player@")),
                    "she did not overhear the stranger: " + brain.host().inbox().entries());
            helper.assertTrue(mind.asked.isEmpty(), "an overheard line woke her");
            brain.close();
            Companions.dismiss(server, creator.getUUID(), List.of(her.getUUID()));
            GameTestKit.leave(stranger);
            GameTestKit.leave(creator);
        });
    }

    /**
     * 群里点名的醒、其余的旁听;她在群里的回话以带会话名的聊天行回给说话的人,同会话的别的成员旁听到;聊天栏切进会话以后打的字走同一个入口、
     * 不广播到公屏。成员表只有 {@code Conversations} 那一张。
     */
    @GameTest(template = "floor16", timeoutTicks = 300, batch = "numen_hearing")
    public static void a_group_wakes_the_one_called_the_other_overhears_and_the_chat_bar_goes_there(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        ServerPlayer creator = GameTestKit.presentPlayer(helper, null, "gt_group_creator");
        NumenPlayer a = companion(helper, creator.getUUID(), "gt_group_a", 6);
        NumenPlayer b = companion(helper, creator.getUUID(), "gt_group_b", 10);
        BrainGameTests.Scripted mindA = new BrainGameTests.Scripted().say("来了").say("听到了");
        BrainGameTests.Scripted mindB = new BrainGameTests.Scripted().say("不该答");
        GameBrain brainA = GameBrain.sit(server, a, mindA);
        GameBrain brainB = GameBrain.sit(server, b, mindB);
        Conversations rooms = Conversations.of(server, creator.getUUID());
        var group = rooms.pullIn(rooms.of(a.getUUID()), b.getUUID(), "gt-group");
        helper.assertTrue(rooms.get("gt-group") != null && rooms.get("gt-group").members().size() == 2,
                "the group was not made: " + rooms.all());
        boolean[] step = {false, false};
        List<String> toCreator = new java.util.ArrayList<>();
        GameTestKit.said(creator);

        succeedWhen(helper, () -> {
            helper.assertTrue(brainA.ready() && brainB.ready(), "the brains have not taken their seats");
            if (!step[0]) {
                helper.assertTrue(OwnerInputs.apply(server, creator.getUUID(),
                        new OwnerInput.Say("gt-group", "@gt_group_a 过来一下")).problem() == null, "the words were refused");
                step[0] = true;
            }
            helper.assertTrue(mindA.asked.size() >= 1 && sawQuery(mindA, "过来一下", "venue=\"group\"", "<audience turn=\"1\">gt_group_b</audience>"),
                    "the one called did not wake with the group's words: " + mindA.asked);
            helper.assertTrue(mindB.asked.isEmpty(), "the one who only overheard woke up");
            helper.assertTrue(brainB.host().inbox().entries().stream().anyMatch(e -> e.type().equals(EventTypes.TALK)
                            && e.text().contains("过来一下")), "the other did not overhear: " + brainB.host().inbox().entries());
            GameTestKit.said(creator).forEach(s -> toCreator.add(s.text().getString()));
            helper.assertTrue(toCreator.stream().anyMatch(s -> s.startsWith("[") && s.contains("<gt_group_a> 来了")),
                    "her answer did not come back as a line of the group: " + toCreator);
            if (!step[1]) {
                server.getCommands().performPrefixedCommand(creator.createCommandSourceStack(), "numen chat gt-group");
                helper.assertTrue("gt-group".equals(ChatBar.routeOf(creator.getUUID())), "the chat bar did not go into the group");
                helper.assertTrue(ChatBar.take(creator, "@gt_group_a 再来一句"), "the chat bar line was not taken");
                step[1] = true;
            }
            helper.assertTrue(sawQuery(mindA, "再来一句"), "the chat bar line did not reach her: " + mindA.asked);
            server.getCommands().performPrefixedCommand(creator.createCommandSourceStack(), "numen chat");
            helper.assertTrue(!ChatBar.take(creator, "公屏上的话"), "the chat bar did not come back to public chat");
            brainA.close();
            brainB.close();
            Companions.dismiss(server, creator.getUUID(), List.of(a.getUUID(), b.getUUID()));
            rooms.dissolve(group);
            GameTestKit.leave(creator);
        });
    }
}
