package com.dwinovo.numen.e2e;

import org.junit.jupiter.api.Test;

import static com.dwinovo.numen.e2e.FakeModel.lua;
import static com.dwinovo.numen.e2e.FakeModel.say;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 她在干活时,没装客户端的主人在动作栏上看到她在干什么,用他客户端的语言。 */
class ActionBarTest extends Scenario {

    @Test
    void the_action_bar_shows_what_she_is_doing_in_his_language() throws Exception {
        Bot owner = join("e2e_watcher", "zh_cn");
        summon(owner, "Nao");
        MODEL.whenHeard("等一会", lua("numen.time.wait(8)"), say("等好了。"));
        int mark = owner.mark();

        owner.command("msg Nao 等一会");

        Bot.Message bar = owner.await(mark, m -> m.overlay() && m.text().contains("Nao"), WAIT, "动作栏上她的状态");
        assertTrue((bar.text().contains("执行") || bar.text().contains("等待")) && !bar.text().contains("numen."),
                "the action bar does not say, in Chinese, what she is doing: " + bar.text());
    }
}
