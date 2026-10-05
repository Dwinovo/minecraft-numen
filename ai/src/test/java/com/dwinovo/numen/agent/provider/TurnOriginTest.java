package com.dwinovo.numen.agent.provider;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 回合记得自己是哪家产的:{@code extras} 与 {@code reasoning} 是那一家的私货,
 * 换绑模型之后不能原样回塞——DeepSeek 的 {@code reasoning_content} 递给别家会被 400 拒,
 * Anthropic 的思考签名更是只有它自己认。而换绑只是面板上一个按钮的事。
 */
class TurnOriginTest {

    private static AssistantTurn stamped(String origin) {
        JsonObject extras = new JsonObject();
        extras.addProperty("reasoning_content", "一路推下来");
        return new AssistantTurn("好", List.of(), extras, "一路推下来").withOrigin(origin);
    }

    @Test
    void sameModelKeepsItsOwnPrivateFields() {
        AssistantTurn turn = stamped("DeepSeekProvider/deepseek-reasoner");
        assertTrue(turn.sameOrigin("DeepSeekProvider/deepseek-reasoner"));
        assertEquals("一路推下来", turn.reasoning());
        assertFalse(turn.extras().entrySet().isEmpty());
    }

    @Test
    void anotherModelGetsTheTurnWithoutThem() {
        AssistantTurn turn = stamped("DeepSeekProvider/deepseek-reasoner");
        assertFalse(turn.sameOrigin("OpenAIProvider/gpt-6-astra"));

        AssistantTurn stripped = turn.withoutProviderPrivateFields();
        assertEquals("好", stripped.content(), "说过的话要留着");
        assertTrue(stripped.extras().entrySet().isEmpty(), "别家的字段不许带过去");
        assertEquals("", stripped.reasoning(), "别家的思考也不许带过去");
    }

    /** 老存档没有出处戳:当对不上处理,宁可少带一份思考,也不要递一份会被拒的请求。 */
    @Test
    void anUnstampedTurnIsTreatedAsForeign() {
        AssistantTurn old = new AssistantTurn("好", List.of(), new JsonObject(), "想了想");
        assertFalse(old.sameOrigin("OpenAIProvider/gpt-6-astra"));
    }
}
