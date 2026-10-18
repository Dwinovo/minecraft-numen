package com.dwinovo.numen.e2e;

import org.junit.jupiter.api.Test;

import java.util.List;

import static com.dwinovo.numen.e2e.FakeModel.say;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 会话指令:没装客户端的主人用 {@code /numen group} 建群、{@code /numen chat} 把聊天栏切进去,聊天栏里的 {@code @} 有补全
 * (切进会话时服务端下发的自定义补全词,与指令里的补全),打的字进会话、被 @ 的她回话。
 */
class ConversationTest extends Scenario {

    @Test
    void a_group_built_by_commands_can_be_chatted_into_with_mention_completion() throws Exception {
        Bot owner = join("e2e_grouper", "en_us");
        summon(owner, "Mia");
        summon(owner, "Rin");

        // 拉进第二只就成了群,起个名字
        int mark = owner.mark();
        owner.command("numen group invite Mia Rin");
        owner.await(mark, m -> m.text().contains("Pulled Rin into"), WAIT, "拉人的回话");
        // 成了群以后会话不再叫她的名字;它叫什么,问服务端对会话参数的补全
        String group = owner.suggest("/numen group rename ", WAIT).matches().stream()
                .filter(title -> title.contains("Mia") && title.contains("Rin")).findFirst()
                .orElseThrow(() -> new AssertionError("the group is not offered as a conversation"));
        owner.command("numen group rename " + group + " Duo");
        owner.await(mark, m -> m.text().contains("is now called 'Duo'"), WAIT, "改名的回话");

        // 切进会话:服务端回一句说明,并把成员的 @ 设成聊天框的补全词
        owner.command("numen chat Duo");
        owner.await(mark, m -> m.text().contains("Your chat now goes to Duo"), WAIT, "切进会话的回话");
        List<String> words = owner.awaitCompletionWords(w -> w.containsAll(List.of("@Mia", "@Rin")), WAIT,
                "会话成员的补全词");
        assertEquals(2, words.size(), "the completion words are not just the members: " + words);

        // 指令里的补全:/numen chat Duo @ 后面补成成员名(与补全词同一个来源)
        Bot.Suggestions suggested = owner.suggest("/numen chat Duo @", WAIT);
        assertTrue(suggested.matches().containsAll(List.of("@Mia", "@Rin")),
                "the command did not complete the members: " + suggested);

        // 聊天栏打的字进会话:先回显一行带会话名的,被 @ 的她回话,回话也带会话名
        MODEL.whenHeard("来点水", say("水来了。"));
        mark = owner.mark();
        owner.say("@Mia 来点水");
        owner.await(mark, m -> m.text().equals("[Duo] <e2e_grouper> @Mia 来点水"), WAIT, "会话里自己那句的回显");
        owner.await(mark, m -> m.text().equals("[Duo] <Mia> 水来了。"), WAIT, "被 @ 的她在会话里的回话");
        assertTrue(MODEL.wasToldAbout("来点水"), "the mention never reached her brain");

        // 切回公屏:补全词收掉
        owner.command("numen chat");
        owner.awaitCompletionWords(List::isEmpty, WAIT, "补全词收掉");
    }
}
