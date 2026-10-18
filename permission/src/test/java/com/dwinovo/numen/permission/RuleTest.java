package com.dwinovo.numen.permission;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 规则文本的解析、打印、匹配、理由与"允许并记住"的推法——全是纯逻辑,对象由 {@link FakeAct} 说了算。 */
class RuleTest {

    private static Rule rule(String text) {
        return Rule.parse(text, FakeAct.VOCAB);
    }

    private static FakeAct breaking() {
        return FakeAct.of(Verb.BREAK);
    }

    // ==================== 解析与打印 ====================

    @Test
    void printsTheCanonicalForm() {
        assertEquals("break(mine & !hollow)", rule("break(mine & !hollow)").toString());
        assertEquals("break(mine & !hollow)", rule("  break( mine&!hollow )  ").toString(), "空白不是内容");
        assertNull(rule("*(mine)").verb(), "* 是任何动作");
        assertEquals(Verb.BREAK, rule("break(mine)").verb());
        assertEquals("break(near(mine))", rule("break(near( mine ))").toString(), "括号参数原样保留");
        assertEquals("break(mine & theirs & hollow)", rule("break(mine & (theirs & hollow))").toString(),
                "同级的 & 摊平,不留多余括号");
        assertEquals(rule("break(mine & !hollow)"), rule("break(mine&!hollow)"), "同一个意思只有一种写法,可以直接比较");
    }

    @Test
    void notBindsTighterThanAndAndAndTighterThanOr() {
        assertEquals("break(mine | theirs & hollow)", rule("break(mine | theirs & hollow)").toString());
        assertEquals("break((mine | theirs) & hollow)", rule("break((mine | theirs) & hollow)").toString(),
                "Or 在 And 里要括号");
        assertEquals("break(!(mine | theirs))", rule("break(!(mine | theirs))").toString());
        assertEquals("break(!mine & !hollow)", rule("break(!mine & !hollow)").toString());
        assertEquals("break(mine | theirs | hollow)", rule("break(mine | (theirs | hollow))").toString());
    }

    @Test
    void mistakesAreTaughtAndCarryTheirCodeAndWords() {
        assertTrue(assertThrows(IllegalArgumentException.class, () -> rule("break mine"))
                .getMessage().contains("verb(term & term)"));
        Rule.Mistake verb = assertThrows(Rule.Mistake.class, () -> rule("chew(mine)"));
        assertEquals("verb", verb.code());
        assertEquals("chew", verb.args().get(0));
        assertEquals("chew(mine)", verb.args().get(1));
        assertTrue(verb.getMessage().contains("break") && verb.getMessage().contains("command"), "列出认得的动词");

        Rule.Mistake name = assertThrows(Rule.Mistake.class, () -> rule("break(haunted)"));
        assertEquals("name", name.code());
        assertEquals("haunted", name.args().get(0));
        assertTrue(name.args().get(2).contains("mine") && name.args().get(2).contains("near(mine)"),
                "列出认得的组与属性,带参数的写出例子");

        assertEquals("empty", assertThrows(Rule.Mistake.class, () -> rule("break()")).code());
        assertEquals("uuid", assertThrows(Rule.Mistake.class, () -> rule("attack(entity:nope)")).code());
        assertEquals("tag", assertThrows(Rule.Mistake.class, () -> rule("break(#Bad Tag)")).code());
        assertEquals("id", assertThrows(Rule.Mistake.class, () -> rule("break(minecraft:Chest)")).code());
        assertEquals("empty_term", assertThrows(Rule.Mistake.class, () -> rule("break(mine &)")).code());
        assertEquals("empty_term", assertThrows(Rule.Mistake.class, () -> rule("break(| mine)")).code());
        assertEquals("syntax", assertThrows(Rule.Mistake.class, () -> rule("break(mine hollow)")).code(), "漏了运算符");
        assertEquals("shape", assertThrows(Rule.Mistake.class, () -> rule("break((mine)")).code(), "括号没配上");
        assertEquals("subject", assertThrows(Rule.Mistake.class, () -> rule("break(mine) extra")).code());
        assertEquals("subject", assertThrows(Rule.Mistake.class, () -> rule("break(mine) @")).code());
    }

    @Test
    void argumentsAreCheckedAgainstWhatTheNameTakes() {
        assertEquals("argument_missing", assertThrows(Rule.Mistake.class, () -> rule("break(near)")).code());
        assertEquals("argument_unexpected", assertThrows(Rule.Mistake.class, () -> rule("break(mine(theirs))")).code());
        Rule.Mistake bad = assertThrows(Rule.Mistake.class, () -> rule("break(near(nobody))"));
        assertEquals("argument_bad", bad.code());
        assertEquals("nobody", bad.args().get(0));
        assertEquals("mine", bad.args().get(3), "教例子");
    }

    @Test
    void anOptionalArgumentMayBeLeftOutButIfGivenItIsChecked() {
        assertEquals("break(inside)", rule("break(inside)").toString());
        assertEquals("break(inside(#minecraft:village))", rule("break(inside( #minecraft:village ))").toString());
        assertTrue(rule("break(inside)").matches(breaking().with("inside")), "不带参数问的是这一类");
        assertTrue(rule("break(inside(#minecraft:village))").matches(breaking().with("inside(#minecraft:village)")));
        assertFalse(rule("break(inside(#minecraft:village))").matches(breaking().with("inside")));
        assertEquals("argument_bad", assertThrows(Rule.Mistake.class, () -> rule("break(inside(village))")).code());
    }

    @Test
    void commandRulesNameRootsAndTeachTheSlashMistake() {
        assertEquals("command(!msg & !trigger)", rule("command(!msg & !trigger)").toString());
        assertEquals("command_name", assertThrows(Rule.Mistake.class, () -> rule("command(/tp)")).code());
        assertEquals("name", assertThrows(Rule.Mistake.class, () -> rule("break(tp)")).code(),
                "别的动词里的裸词仍要是登记的名字");
    }

    // ==================== 主体 ====================

    @Test
    void aRuleMayNameTheOneCompanionItIsFor() {
        Rule scoped = rule("break(mine & !hollow) @Aria");
        assertEquals("break(mine & !hollow) @Aria", scoped.toString());
        assertEquals("Aria", scoped.companion());
        assertEquals(scoped, rule("  break( mine&!hollow )   @Aria "), "写法规范以后可以直接比较");
        assertNull(rule("break(mine)").companion());
        assertEquals("break(mine) @小蓝", rule("break(mine) @小蓝").toString(), "名字不限英文");

        FakeAct byAria = new FakeAct(Verb.BREAK, "Aria").with("mine");
        FakeAct byBea = new FakeAct(Verb.BREAK, "Bea").with("mine");
        assertTrue(scoped.matches(byAria));
        assertFalse(scoped.matches(byBea), "别的同伴不受这一行管");
        assertFalse(scoped.matches(breaking().with("mine")), "不知道是谁动手,不算点名的那一只");
        assertTrue(rule("break(mine)").matches(byBea), "不写 @ 就是每一只");
    }

    // ==================== 匹配 ====================

    @Test
    void verbMustMatchUnlessWildcard() {
        FakeAct place = FakeAct.of(Verb.PLACE);
        assertFalse(rule("break(*)").matches(place));
        assertTrue(rule("place(*)").matches(place));
        assertTrue(rule("*(*)").matches(place));
    }

    @Test
    void andOrNotAndParenthesesEvaluate() {
        FakeAct mineHollow = breaking().with("mine", "hollow");
        assertTrue(rule("break(mine & hollow)").matches(mineHollow));
        assertFalse(rule("break(mine & !hollow)").matches(mineHollow));
        assertTrue(rule("break(theirs | hollow)").matches(mineHollow));
        assertFalse(rule("break(theirs | stuffed)").matches(mineHollow));
        assertTrue(rule("break((mine | theirs) & hollow)").matches(mineHollow));
        assertFalse(rule("break(!(mine | theirs))").matches(mineHollow));
        assertTrue(rule("break(!(theirs | stuffed))").matches(mineHollow));
        assertTrue(rule("break(mine | theirs & stuffed)").matches(mineHollow), "& 先于 |");
    }

    @Test
    void kindTagEntityCommandAndArgumentTermsAskTheObject() {
        FakeAct chest = breaking().kind("minecraft:chest").tag("minecraft:beds").with("near(mine)");
        assertTrue(rule("break(minecraft:chest)").matches(chest));
        assertFalse(rule("break(minecraft:barrel)").matches(chest));
        assertTrue(rule("break(#minecraft:beds)").matches(chest));
        assertFalse(rule("break(#minecraft:doors)").matches(chest));
        assertTrue(rule("break(near(mine))").matches(chest));
        assertFalse(rule("break(near(theirs))").matches(chest), "参数不同是另一个问题");
        String uuid = "00000000-0000-0000-0000-0000000000aa";
        assertTrue(rule("attack(entity:" + uuid + ")").matches(FakeAct.of(Verb.ATTACK).entity(uuid)));
        assertFalse(rule("attack(entity:" + uuid + ")").matches(FakeAct.of(Verb.ATTACK)));
        FakeAct tp = FakeAct.of(Verb.COMMAND).command("tp", "teleport");
        assertTrue(rule("command(teleport)").matches(tp), "别名同认");
        assertTrue(rule("command(!msg)").matches(tp));
        assertFalse(rule("command(msg)").matches(tp));
        assertFalse(rule("drop(*)").matches(tp), "别的动词不认指令");
    }

    // ==================== 理由与撤不回 ====================

    @Test
    void reasonsAreTheTrueTermsNotUnderANegation() {
        Rule r = rule("break(mine & !hollow)");
        FakeAct act = breaking().with("mine");
        assertEquals("belongs to the owner", r.describe(act), "取反的项不进自述");
        assertEquals("belongs to the owner, has a name", rule("break(mine & named)").describe(breaking().with("mine", "named")));
        assertEquals("is #minecraft:beds", rule("break(#minecraft:beds)").describe(breaking().tag("minecraft:beds")));
        assertEquals("runs /setblock", rule("command(setblock)").describe(FakeAct.of(Verb.COMMAND).command("setblock")));
        assertEquals("break(*)", rule("break(*)").describe(breaking()), "没有可说的就是规则原文");
        assertEquals("belongs to somebody else", rule("break(mine | theirs)").describe(breaking().with("theirs")),
                "Or 命中的是哪一支就只说哪一支");
    }

    @Test
    void whatCannotBeUndoneIsAskedOfTheActNotOfTheRule() {
        assertTrue(FakeAct.VOCAB.irreversible(breaking().with("stuffed")));
        assertTrue(FakeAct.VOCAB.irreversible(FakeAct.of(Verb.ATTACK).with("stuffed")));
        assertFalse(FakeAct.VOCAB.irreversible(breaking().with("hollow")));
        assertFalse(FakeAct.VOCAB.irreversible(FakeAct.of(Verb.TAKE).with("stuffed")), "撤不回是按动词登记的");
        assertFalse(FakeAct.VOCAB.irreversible(breaking()), "对象身上不成立就不算");
    }

    // ==================== 允许并记住 ====================

    private static Rule remembering(FakeAct now, FakeAct blind, String hit) {
        return Rule.remembering(now, blind, hit == null ? null : rule(hit), FakeAct.VOCAB);
    }

    @Test
    void rememberingKeepsTheAskedConditionsAndPinsTheKind() {
        FakeAct cobble = breaking().with("mine").kind("minecraft:cobblestone");
        Rule remembered = remembering(cobble, cobble, "break(mine)");
        assertEquals("break(mine & minecraft:cobblestone)", remembered.toString());
        assertTrue(remembered.matches(cobble), "记下的规则盖得住这次问的动作");

        FakeAct stone = breaking().with("hollow").kind("minecraft:stone");
        assertEquals("break(!mine & !hollow & minecraft:stone)",
                remembering(stone, stone, "break(!mine & !hollow)").toString(), "取反项原样留着");
        assertEquals("break((mine | theirs) & minecraft:cobblestone)",
                remembering(cobble, cobble, "break(mine | theirs)").toString(), "Or 在 And 里加括号");
        assertEquals("break(minecraft:stone)", remembering(stone, stone, null).toString(), "没有规则覆盖:只钉对象");
        FakeAct byAria = new FakeAct(Verb.BREAK, "Aria").with("mine").kind("minecraft:cobblestone");
        assertEquals("break(mine & minecraft:cobblestone) @Aria", remembering(byAria, byAria, "break(mine) @Aria").toString(),
                "问出它的那一行限定了同伴,记下的也只管那一只");
    }

    @Test
    void rememberingPinsTheIrreversibleNamesThatDidNotHoldThisTime() {
        // 活世界里这只箱子是空的,搜索线程读不到内容按有:不钉的话,记下的规则连装着东西的箱子也盖
        FakeAct live = breaking().with("hollow").kind("minecraft:chest");
        FakeAct blind = breaking().with("hollow", "stuffed").kind("minecraft:chest");
        assertEquals("break(hollow & minecraft:chest & !stuffed)", remembering(live, blind, "break(hollow)").toString());
        // 本来就装着东西:没什么可钉
        assertEquals("break(hollow & minecraft:chest)", remembering(blind, blind, "break(hollow)").toString());
        // 问出它的那一行已经说到了:不再添
        assertEquals("break(hollow & stuffed & minecraft:chest)",
                remembering(blind, blind, "break(hollow & stuffed)").toString());
        // 两边都不装东西:不钉
        FakeAct bare = breaking().with("hollow").kind("minecraft:chest");
        assertEquals("break(hollow & minecraft:chest)", remembering(bare, bare, "break(hollow)").toString());
    }

    @Test
    void rememberingAnEntityNamesThatOneAndACommandNamesItsRoot() {
        String uuid = "00000000-0000-0000-0000-0000000000aa";
        FakeAct wolf = FakeAct.of(Verb.ATTACK).with("named").kind("minecraft:wolf").entity(uuid);
        assertEquals("attack(entity:" + uuid + ")", remembering(wolf, wolf, "attack(named)").toString());

        FakeAct setblock = FakeAct.of(Verb.COMMAND).command("setblock");
        assertEquals("command(setblock)", remembering(setblock, setblock, null).toString());
    }
}
