package com.dwinovo.numen.e2e;

import com.dwinovo.numen.api.owner.OwnerInput;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static com.dwinovo.numen.e2e.FakeModel.say;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 会话:主人在面板上拉人建群(上行的是面板发的那个包),{@code /numen chat} 把聊天栏切进去,聊天栏里的 {@code @} 有补全(切进会话时
 * 服务端下发的自定义补全词,与指令里的补全),打的字进会话、被 @ 的她回话——话由服务端按成员表送到各自的座位,回话带会话名回在聊天里。
 */
class ConversationTest extends Scenario {

    @Test
    void a_group_built_on_the_panel_can_be_chatted_into_with_mention_completion() throws Exception {
        HeadlessOwner owner = joinOwner("e2e_grouper", "en_us");
        UUID mia = summon(owner, "Mia");
        UUID rin = summon(owner, "Rin");

        // 拉进第二只就成了群;号由面板起
        owner.ask(new OwnerInput.Pull(mia.toString(), rin, "duo"));
        owner.ask(new OwnerInput.Rename("duo", "Duo"));
        Bot bot = owner.bot();
        int mark = bot.mark();

        // 切进会话:服务端回一句说明,并把成员的 @ 设成聊天框的补全词
        bot.command("numen chat Duo");
        bot.await(mark, m -> "numen.command.chat.entered".equals(Text.key(m.content()))
                && Text.arguments(m.content()).contains("Duo"), WAIT, "切进会话的回话");
        List<String> words = bot.awaitCompletionWords(w -> w.containsAll(List.of("@Mia", "@Rin")), WAIT,
                "会话成员的补全词");
        assertEquals(2, words.size(), "the completion words are not just the members: " + words);

        // 指令里的补全:/numen chat Duo @ 后面补成成员名(与补全词同一个来源)
        Bot.Suggestions suggested = bot.suggest("/numen chat Duo @", WAIT);
        assertTrue(suggested.matches().containsAll(List.of("@Mia", "@Rin")),
                "the command did not complete the members: " + suggested);

        // 聊天栏打的字进会话:先回显一行带会话名的,被 @ 的她回话,回话也带会话名;没被点名的那一只只是旁听
        MODEL.whenHeard("来点水", say("水来了。"));
        mark = bot.mark();
        bot.say("@Mia 来点水");
        bot.await(mark, m -> m.text().equals("[Duo] <e2e_grouper> @Mia 来点水"), WAIT, "会话里自己那句的回显");
        bot.await(mark, m -> m.text().equals("[Duo] <Mia> 水来了。"), WAIT, "被 @ 的她在会话里的回话");
        assertTrue(MODEL.wasToldAbout("来点水"), "the mention never reached her brain");
        owner.until("Rin overheard the group", () -> owner.inboxHas(rin, "来点水"));

        // 切回公屏:补全词收掉
        bot.command("numen chat");
        bot.awaitCompletionWords(List::isEmpty, WAIT, "补全词收掉");
    }
}
