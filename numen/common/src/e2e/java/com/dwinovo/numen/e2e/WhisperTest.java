package com.dwinovo.numen.e2e;

import org.junit.jupiter.api.Test;

import static com.dwinovo.numen.e2e.FakeModel.say;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 召唤她、用原版私聊跟她说话:她是玩家列表里的一个真玩家;主人 {@code /msg} 她,话到了她的大脑(假模型被问到),
 * 她的回话以原版私信的样子回到主人手里。
 */
class WhisperTest extends Scenario {

    @Test
    void a_summoned_companion_is_a_player_who_answers_a_private_message() throws Exception {
        Bot owner = join("e2e_whisperer", "zh_cn");
        summon(owner, "Mia");
        assertTrue(console("list").contains("Mia"), "she is not on the player list");
        MODEL.whenHeard("你好", say("你好呀,我在。"));
        int mark = owner.mark();

        owner.command("msg Mia 你好");

        Bot.Message reply = owner.await(mark, m -> "commands.message.display.incoming".equals(Text.key(m.content()))
                && Text.arguments(m.content()).contains("你好呀,我在。"), WAIT, "她以私信回的话");
        assertTrue(Text.arguments(reply.content()).get(0).equals("Mia"), "the whisper is not from her: " + reply.text());
        assertTrue(MODEL.wasToldAbout("你好"), "her brain was never asked about the whisper");
    }
}
