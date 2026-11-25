package com.dwinovo.numen.e2e;

import org.junit.jupiter.api.Test;

import static com.dwinovo.numen.e2e.FakeModel.say;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 用原版私聊跟她说话:她是玩家列表里的一个真玩家;主人 {@code /msg} 她,服务端把话送到主人客户端上她的大脑(假模型被问到),
 * 她的回话由服务端以原版私信的样子回到主人手里。不是她的 keeper 的玩家私聊她,只是旁听,不叫醒她。
 */
class WhisperTest extends Scenario {

    @Test
    void a_summoned_companion_is_a_player_who_answers_a_private_message() throws Exception {
        HeadlessOwner owner = joinOwner("e2e_whisperer", "zh_cn");
        summon(owner, "Mia");
        assertTrue(console("list").contains("Mia"), "she is not on the player list");
        MODEL.whenHeard("你好", say("你好呀,我在。"));
        int mark = owner.bot().mark();

        owner.bot().command("msg Mia 你好");

        Bot.Message reply = owner.bot().await(mark, m -> "commands.message.display.incoming".equals(Text.key(m.content()))
                && Text.arguments(m.content()).contains("你好呀,我在。"), WAIT, "她以私信回的话");
        assertTrue(Text.arguments(reply.content()).get(0).equals("Mia"), "the whisper is not from her: " + reply.text());
        assertTrue(MODEL.wasToldAbout("你好"), "her brain was never asked about the whisper");
    }

    @Test
    void a_strangers_private_message_is_only_overheard() throws Exception {
        HeadlessOwner owner = joinOwner("e2e_host_of_pet", "en_us");
        summon(owner, "Nao");
        Bot stranger = join("e2e_stranger", "en_us");
        MODEL.whenHeard("psst", say("should not answer"));

        stranger.command("msg Nao psst");
        Thread.sleep(2000);

        assertFalse(MODEL.wasToldAbout("psst"), "a stranger's whisper woke her");
    }
}
