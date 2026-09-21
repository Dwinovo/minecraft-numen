package com.dwinovo.numen.agent.conversation;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 群里说一句话,谁醒。
 *
 * <p>这套测试守的是群聊唯一的那条不变量能落到实处:<b>唤醒只能由主人产生</b>。
 * 路由只回答"这一句叫醒谁",它不认识同伴说的话——同伴的话根本不走这条路。
 */
class MentionsTest {

    private static final UUID YOU = UUID.randomUUID();     // 小柚
    private static final UUID LAN = UUID.randomUUID();     // 阿岚
    private static final UUID MEI = UUID.randomUUID();     // 小梅

    private static final List<Mentions.Member> GROUP = List.of(
            new Mentions.Member(YOU, "小柚"),
            new Mentions.Member(LAN, "阿岚"),
            new Mentions.Member(MEI, "小梅"));

    // ---- @ 谁谁回,不 @ 全体回 ----

    @Test
    void mentioningSomeoneWakesOnlyHer() {
        Mentions.Routing r = Mentions.route("@小柚 去挖点铁", GROUP);
        assertEquals(List.of(YOU), r.awake());
    }

    /** 点名只是那一句的事:下一句没点名就是说给大家的,她也在其中。 */
    @Test
    void theNextLineWithoutAMentionGoesToEveryoneAgain() {
        Mentions.route("@小柚 去挖点铁", GROUP);
        Mentions.Routing second = Mentions.route("多挖点", GROUP);
        assertEquals(List.of(YOU, LAN, MEI), second.awake(), "上一句点了谁不粘住");
    }

    @Test
    void mentioningTwoWakesBoth() {
        Mentions.Routing r = Mentions.route("@小柚 @阿岚 一起去挖铁", GROUP);
        assertEquals(List.of(YOU, LAN), r.awake());
    }

    @Test
    void mentionsComeBackInTheOrderTheyAppear() {
        Mentions.Routing r = Mentions.route("@小梅 和 @小柚 一起来", GROUP);
        assertEquals(List.of(MEI, YOU), r.awake());
    }

    // ---- 没点名 ----

    /** 没点名的话,通常本来就是说给全体的。 */
    @Test
    void withNoMentionEveryoneWakes() {
        Mentions.Routing r = Mentions.route("我回来了", GROUP);
        assertEquals(List.of(YOU, LAN, MEI), r.awake());
    }

    // ---- 名字怎么认 ----

    /** 名字互为前缀是最容易踩的坑:@Anna 里含着 Ann。 */
    @Test
    void aLongerNameWinsOverItsOwnPrefix() {
        UUID ann = UUID.randomUUID();
        UUID anna = UUID.randomUUID();
        List<Mentions.Member> two = List.of(
                new Mentions.Member(ann, "Ann"),
                new Mentions.Member(anna, "Anna"));

        assertEquals(List.of(anna), Mentions.mentioned("@Anna 过来", two));
        assertEquals(List.of(ann), Mentions.mentioned("@Ann 过来", two));
        assertEquals(List.of(anna, ann), Mentions.mentioned("@Anna 和 @Ann 都来", two));
    }

    @Test
    void anAtInTheMiddleOfASentenceStillCounts() {
        assertEquals(List.of(LAN), Mentions.mentioned("那件事 @阿岚 你怎么看", GROUP));
    }

    @Test
    void caseDoesNotMatter() {
        UUID bob = UUID.randomUUID();
        List<Mentions.Member> one = List.of(new Mentions.Member(bob, "Bob"));
        assertEquals(List.of(bob), Mentions.mentioned("@bob come here", one));
    }

    @Test
    void aNameWithoutTheAtIsNotAMention() {
        assertEquals(List.of(), Mentions.mentioned("阿岚今天挖了不少", GROUP),
                "说到她不等于喊她");
    }

    // ---- 区间:面板把名字画亮用的就是这一份匹配 ----

    @Test
    void spansCoverExactlyTheMentionedNames() {
        String line = "@小柚 和 @阿岚 一起去";
        List<Mentions.Span> spans = Mentions.spans(line, GROUP);
        assertEquals(2, spans.size());
        assertEquals("@小柚", line.substring(spans.get(0).start(), spans.get(0).end()));
        assertEquals(List.of(YOU), spans.get(0).whom());
        assertEquals("@阿岚", line.substring(spans.get(1).start(), spans.get(1).end()));
        assertEquals(List.of(LAN), spans.get(1).whom());
    }

    @Test
    void spansComeInOrderOfAppearance() {
        List<Mentions.Span> spans = Mentions.spans("@阿岚 先,@小柚 后", GROUP);
        assertTrue(spans.get(0).start() < spans.get(1).start());
        assertEquals(List.of(LAN), spans.get(0).whom());
        assertEquals(List.of(YOU), spans.get(1).whom());
    }

    @Test
    void anUnknownNameMentionsNobody() {
        assertEquals(List.of(), Mentions.mentioned("@谁啊 在吗", GROUP));
    }

    @Test
    void sayingTheSameNameTwiceStillWakesHerOnce() {
        assertEquals(List.of(YOU), Mentions.mentioned("@小柚 你听见没 @小柚", GROUP));
    }

    /**
     * 两只同伴重名时,喊那个名字就是把她们都喊上。
     *
     * <p>名字不是唯一的(召唤时不拦重名),而"按成员顺序挑第一只"挑中谁完全取决于建群时
     * 勾选的先后——主人看不见那个顺序,也就无从预料谁会应声。宁可两只都来。
     */
    @Test
    void twoCompanionsSharingANameBothWakeUp() {
        UUID one = UUID.randomUUID();
        UUID two = UUID.randomUUID();
        List<Mentions.Member> twins = List.of(
                new Mentions.Member(one, "小柚"),
                new Mentions.Member(two, "小柚"));
        assertEquals(List.of(one, two), Mentions.mentioned("@小柚 过来", twins));
    }
}
