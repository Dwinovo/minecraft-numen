package com.dwinovo.numen.permission;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 判定顺序:模式 → 硬规则 → 任何一层的 deny → 各层高到低、层内 allow 先于 ask → 都不中也问;任务期授权只把问变成放行。
 * 与书写顺序无关。
 */
class PolicyTest {

    private static Rule rule(String text) {
        return Rule.parse(text, FakeAct.VOCAB);
    }

    private static List<Rule> rules(String... texts) {
        List<Rule> out = new ArrayList<>();
        for (String t : texts) {
            out.add(rule(t));
        }
        return out;
    }

    private static RuleSet layer(List<String> deny, List<String> ask, List<String> allow) {
        return new RuleSet(rules(deny.toArray(String[]::new)), rules(ask.toArray(String[]::new)),
                rules(allow.toArray(String[]::new)));
    }

    private static Policy policy(Mode mode, RuleSet... layers) {
        return new Policy(mode, List.of(), List.of(layers));
    }

    private static FakeAct breaking() {
        return FakeAct.of(Verb.BREAK);
    }

    // ==================== 层与顺序 ====================

    @Test
    void anAllowRowCarvesOutOfAnAskRowOfTheSameLayerWhateverTheWritingOrder() {
        RuleSet owner = layer(List.of(), List.of("break(mine)"), List.of("break(mine & minecraft:cobblestone)"));
        Policy policy = policy(Mode.ASK, owner);
        assertTrue(policy.judge(breaking().with("mine").kind("minecraft:cobblestone"), Policy.NO_GRANTS).allowed());
        Verdict log = policy.judge(breaking().with("mine").kind("minecraft:oak_log"), Policy.NO_GRANTS);
        assertTrue(log.asks(), "没被记住的照旧问");
        assertEquals("break(mine)", log.rule().toString());
    }

    @Test
    void denyBeatsAnAllowOnAnyLayerInEitherDirection() {
        FakeAct dirt = breaking().kind("minecraft:dirt");
        RuleSet allowAll = layer(List.of(), List.of(), List.of("break(*)"));
        RuleSet denyDirt = layer(List.of("break(minecraft:dirt)"), List.of(), List.of());

        Verdict ownerDeny = policy(Mode.ASK, denyDirt, allowAll).judge(dirt, Policy.NO_GRANTS);
        assertEquals(Verdict.Kind.DENY, ownerDeny.kind());
        assertTrue(ownerDeny.reason().contains("break(minecraft:dirt)"), "理由里点名那一行");
        Verdict lowerDeny = policy(Mode.ASK, allowAll, denyDirt).judge(dirt, Policy.NO_GRANTS);
        assertEquals(Verdict.Kind.DENY, lowerDeny.kind(), "高层的 allow 盖不过低层的 deny:明确的禁止永远赢");
    }

    @Test
    void aHigherLayerAskOrAllowBeatsALowerLayerAllowOrAsk() {
        FakeAct stone = breaking().kind("minecraft:stone");
        RuleSet factoryAllow = layer(List.of(), List.of(), List.of("break(*)"));
        RuleSet ownerAsk = layer(List.of(), List.of("break(minecraft:stone)"), List.of());
        Verdict asked = policy(Mode.ASK, ownerAsk, factoryAllow).judge(stone, Policy.NO_GRANTS);
        assertTrue(asked.asks(), "主人写了要问,出厂的 allow 压不过它");
        assertEquals("break(minecraft:stone)", asked.rule().toString());

        FakeAct mine = breaking().with("mine");
        RuleSet factoryAsk = layer(List.of(), List.of("break(mine)"), List.of());
        RuleSet ownerAllow = layer(List.of(), List.of(), List.of("break(mine)"));
        assertTrue(policy(Mode.ASK, ownerAllow, factoryAsk).judge(mine, Policy.NO_GRANTS).allowed(),
                "主人的 allow 压过出厂 ask");
    }

    @Test
    void nothingMatchedMeansAsk() {
        Verdict v = policy(Mode.ASK, RuleSet.EMPTY).judge(breaking().kind("minecraft:stone"), Policy.NO_GRANTS);
        assertTrue(v.asks(), "没有一行规则说过的事去问主人,不放行");
        assertFalse(v.allowed());
        assertNull(v.rule());
        assertEquals(Verdict.UNCOVERED, v.cause());
    }

    @Test
    void theCauseOfAnAskIsWhatHitTheRuleForThisAct() {
        Policy policy = policy(Mode.ASK, layer(List.of(), List.of("break(mine | theirs)"), List.of()));
        Verdict v = policy.judge(breaking().with("theirs"), Policy.NO_GRANTS);
        assertEquals("belongs to somebody else", v.cause());
        assertEquals("belongs to somebody else: needs the owner's consent", v.reason());
    }

    @Test
    void theVerdictNamesTheRowThatDecidedAndTheLayerItIsOn() {
        RuleSet top = layer(List.of(), List.of("break(mine)"), List.of("break(theirs)"));
        RuleSet bottom = layer(List.of("break(hollow)"), List.of(), List.of("break(minecraft:dirt)"));
        Policy policy = policy(Mode.ASK, top, bottom);

        Verdict allowed = policy.judge(breaking().with("theirs"), Policy.NO_GRANTS);
        assertEquals(Verdict.Kind.ALLOW, allowed.kind());
        assertEquals("break(theirs)", allowed.rule().toString());
        assertEquals(0, allowed.layer());
        Verdict allowedLower = policy.judge(breaking().kind("minecraft:dirt"), Policy.NO_GRANTS);
        assertEquals(1, allowedLower.layer(), "第二层的 allow 行");
        Verdict asked = policy.judge(breaking().with("mine"), Policy.NO_GRANTS);
        assertEquals(0, asked.layer());
        Verdict denied = policy.judge(breaking().with("hollow"), Policy.NO_GRANTS);
        assertEquals("break(hollow)", denied.rule().toString());
        assertEquals(1, denied.layer(), "任何一层的 deny 行");

        Verdict hard = new Policy(Mode.ASK, rules("break(mine)"), List.of(top)).judge(breaking().with("mine"),
                Policy.NO_GRANTS);
        assertEquals(Verdict.HARD, hard.layer());
        assertEquals("break(mine)", hard.rule().toString());

        for (Verdict none : List.of(policy.judge(breaking().kind("minecraft:stone"), Policy.NO_GRANTS),
                policy(Mode.OBSERVE, top).judge(breaking(), Policy.NO_GRANTS),
                policy(Mode.BYPASS, top).judge(breaking(), Policy.NO_GRANTS),
                policy(Mode.ASK, top).judge(breaking().with("mine"), hit -> true))) {
            assertNull(none.rule(), "模式、授权与没有规则覆盖都不是哪一行定的");
            assertEquals(Verdict.NO_LAYER, none.layer());
        }
    }

    // ==================== 模式与硬规则 ====================

    @Test
    void bypassAllowsEverythingAndObserveDeniesEverything() {
        FakeAct act = breaking().with("mine");
        RuleSet asks = layer(List.of(), List.of("break(*)"), List.of());
        assertTrue(policy(Mode.BYPASS, asks).judge(act, Policy.NO_GRANTS).allowed(), "bypass 全放");
        assertTrue(policy(Mode.BYPASS, layer(List.of("break(*)"), List.of(), List.of()))
                .judge(act, Policy.NO_GRANTS).allowed(), "bypass 连 deny 行也放");
        Verdict observed = policy(Mode.OBSERVE, asks).judge(act, Policy.NO_GRANTS);
        assertEquals(Verdict.Kind.DENY, observed.kind());
        assertTrue(observed.reason().contains("observe mode"));
        assertEquals(Verdict.Kind.DENY, policy(Mode.OBSERVE, layer(List.of(), List.of(), List.of("*(*)")))
                .judge(breaking(), Policy.NO_GRANTS).kind(), "observe 连 allow 行放行的也不动");
    }

    @Test
    void hardRulesAreBeyondModesAndLayers() {
        List<Rule> hard = rules("break(theirs)");
        RuleSet allowAll = layer(List.of(), List.of(), List.of("*(*)"));
        FakeAct theirs = breaking().with("theirs");
        for (Mode mode : List.of(Mode.ASK, Mode.BYPASS)) {
            Verdict v = new Policy(mode, hard, List.of(allowAll)).judge(theirs, hit -> true);
            assertEquals(Verdict.Kind.DENY, v.kind(), mode + " 解不开硬规则,授权也解不开");
            assertTrue(v.reason().contains("hard rule break(theirs)"));
        }
        assertTrue(new Policy(Mode.ASK, hard, List.of(allowAll)).judge(breaking().with("mine"), Policy.NO_GRANTS)
                .allowed(), "硬规则只拦它说到的");
    }

    // ==================== 任务期授权 ====================

    @Test
    void aGrantTurnsAnAskIntoAnAllowButNeverLiftsADeny() {
        FakeAct mine = breaking().with("mine");
        RuleSet asks = layer(List.of(), List.of("break(mine)"), List.of());
        assertTrue(policy(Mode.ASK, asks).judge(mine, hit -> hit != null && hit.toString().equals("break(mine)"))
                .allowed());
        assertTrue(policy(Mode.ASK, asks).judge(mine, hit -> false).asks());
        assertTrue(policy(Mode.ASK, RuleSet.EMPTY).judge(mine, hit -> hit == null).allowed(),
                "没有规则覆盖的动作也能被授权覆盖(hit 为 null)");
        assertEquals(Verdict.Kind.DENY, policy(Mode.OBSERVE, asks).judge(mine, hit -> true).kind());
        assertEquals(Verdict.Kind.DENY,
                policy(Mode.ASK, layer(List.of("break(mine)"), List.of(), List.of())).judge(mine, hit -> true).kind());
    }
}
