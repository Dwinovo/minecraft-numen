package com.dwinovo.numen.agent.group;

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

    // ---- @ 转话头 ----

    @Test
    void mentioningSomeoneWakesHerAndHandsHerTheFloor() {
        Mentions.Routing r = Mentions.route("@小柚 去挖点铁", GROUP, List.of());
        assertEquals(List.of(YOU), r.awake());
        assertEquals(List.of(YOU), r.floor(), "话头跟着转过去");
    }

    @Test
    void theFloorHoldsSoTheNextLineNeedsNoMention() {
        Mentions.Routing first = Mentions.route("@小柚 去挖点铁", GROUP, List.of());
        Mentions.Routing second = Mentions.route("多挖点", GROUP, first.floor());
        assertEquals(List.of(YOU), second.awake(), "还是她,不必再打 @");
        assertEquals(List.of(YOU), second.floor());
    }

    @Test
    void mentioningSomeoneElseMovesTheFloor() {
        Mentions.Routing r = Mentions.route("@阿岚 你去砍树", GROUP, List.of(YOU));
        assertEquals(List.of(LAN), r.awake());
        assertEquals(List.of(LAN), r.floor());
    }

    /** 点了两个,之后那句"小心点"该让两只都听见,不该塌回其中一只。 */
    @Test
    void mentioningTwoGivesThemBothTheFloor() {
        Mentions.Routing r = Mentions.route("@小柚 @阿岚 一起去挖铁", GROUP, List.of());
        assertEquals(List.of(YOU, LAN), r.awake());

        Mentions.Routing next = Mentions.route("小心点", GROUP, r.floor());
        assertEquals(List.of(YOU, LAN), next.awake());
    }

    @Test
    void mentionsComeBackInTheOrderTheyAppear() {
        Mentions.Routing r = Mentions.route("@小梅 和 @小柚 一起来", GROUP, List.of());
        assertEquals(List.of(MEI, YOU), r.awake());
    }

    // ---- 没点名 ----

    /** 没点过名的时候说的话,通常本来就是说给全体的。 */
    @Test
    void withNoMentionAndNoFloorEveryoneWakes() {
        Mentions.Routing r = Mentions.route("我回来了", GROUP, List.of());
        assertEquals(List.of(YOU, LAN, MEI), r.awake());
        assertTrue(r.floor().isEmpty(), "话头仍然空着:下一句没点名还是全体");
    }

    @Test
    void aFloorMemberWhoLeftTheGroupDoesNotHoldItAnyMore() {
        Mentions.Routing r = Mentions.route("接着挖", GROUP, List.of(UUID.randomUUID()));
        assertEquals(List.of(YOU, LAN, MEI), r.awake(), "话头落空就退回全体");
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

    @Test
    void anUnknownNameMentionsNobody() {
        assertEquals(List.of(), Mentions.mentioned("@谁啊 在吗", GROUP));
    }

    @Test
    void sayingTheSameNameTwiceStillWakesHerOnce() {
        assertEquals(List.of(YOU), Mentions.mentioned("@小柚 你听见没 @小柚", GROUP));
    }
}
