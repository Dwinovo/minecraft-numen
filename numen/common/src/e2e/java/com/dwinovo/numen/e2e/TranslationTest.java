package com.dwinovo.numen.e2e;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 没装我们客户端的玩家读得懂 Numen 的话:服务端按他客户端报上来的语言,把 Numen 的语言键换成字再发,
 * 他收到的是译好的正文,不是 {@code numen.…} 的键名。
 */
class TranslationTest extends Scenario {

    @Test
    void numen_messages_arrive_in_the_language_the_client_reports() throws Exception {
        Bot chinese = join("e2e_zh", "zh_cn");
        Bot english = join("e2e_en", "en_us");
        int chineseMark = chinese.mark();
        int englishMark = english.mark();

        // 这条指令作用在 Numen 客户端上,没装的玩家得到一句说明——一条不依赖任何前置状态的 Numen 系统消息
        chinese.command("numen settings");
        english.command("numen settings");

        Bot.Message zh = chinese.await(chineseMark, m -> m.text().contains("要装了 Numen 客户端"), WAIT,
                "中文的说明");
        Bot.Message en = english.await(englishMark, m -> m.text().contains("This needs the Numen client"), WAIT,
                "英文的说明");
        for (Bot.Message message : new Bot.Message[] {zh, en}) {
            assertNull(Text.key(message.content()), "the client was sent a translation key: " + message.text());
            assertTrue(!message.text().contains("numen."), "a Numen key is left in the text: " + message.text());
        }
    }
}
