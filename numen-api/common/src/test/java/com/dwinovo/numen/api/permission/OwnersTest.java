package com.dwinovo.numen.api.permission;

import com.dwinovo.numen.api.Questions;
import com.dwinovo.numen.api.agent.scope.Handle;

import net.minecraft.core.BlockPos;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * "这是谁的"问一圈再换算:没人回答就是没主人的;回答者说的那个人换成她自己、主人这一边、别人;几个回答者说的不是同一个人取最严的
 * (别人 > 主人 > 自己),不看谁先登记;抛异常的回答者只丢它那一条。方块与实体走的是同一个换算。
 */
class OwnersTest {

    private static final UUID OWNER_ID = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
    private static final UUID HER_ID = UUID.fromString("00000000-0000-0000-0000-0000000000bb");
    private static final UUID SISTER_ID = UUID.fromString("00000000-0000-0000-0000-0000000000cc");
    private static final UUID STRANGER_ID = UUID.fromString("00000000-0000-0000-0000-0000000000dd");

    /** 要动手的是她:主人 Steve,主人名下还有一只 Bea。 */
    private static final Actor HER = new Actor(HER_ID, "Aria", OWNER_ID, java.util.Set.of(SISTER_ID));

    private static final BlockPos POS = new BlockPos(1, 64, 1);

    /** 登记着 {@code answerers}(名字到回答者,照表里的先后)时跑 {@code body},跑完撤掉。 */
    private static void withBlockOwners(Map<String, BlockOwner> answerers, Runnable body) {
        List<Handle> handles = new ArrayList<>();
        try {
            answerers.forEach((name, answerer) -> handles.add(Questions.BLOCK_OWNER.register(name, answerer)));
            body.run();
        } finally {
            handles.forEach(Handle::close);
        }
    }

    private static void withEntityOwners(Map<String, EntityOwner> answerers, Runnable body) {
        List<Handle> handles = new ArrayList<>();
        try {
            answerers.forEach((name, answerer) -> handles.add(Questions.ENTITY_OWNER.register(name, answerer)));
            body.run();
        } finally {
            handles.forEach(Handle::close);
        }
    }

    /** 说 {@link #POS} 这一格是 {@code who} 的回答者。 */
    private static BlockOwner saying(UUID who) {
        return (level, center, chunkRadius) -> pos -> POS.equals(pos) ? who : null;
    }

    /** 此刻登记着的回答者说 {@link #POS} 这一格对她是谁的。 */
    private static Whose whoseBlock() {
        Owners.Blocks owners = Owners.around(null, POS, Structures.CAPTURE_RADIUS_CHUNKS);
        return Whose.ofBlock(POS, new Facts(null, null, HER, Structures.NONE, owners, Map.of()));
    }

    private static Map<String, BlockOwner> two(UUID first, UUID second) {
        Map<String, BlockOwner> answerers = new LinkedHashMap<>();
        answerers.put("gt_first", saying(first));
        answerers.put("gt_second", saying(second));
        return answerers;
    }

    @Test
    void nobodyAnsweringMeansNobodys() {
        assertSame(Owners.Blocks.NONE, Owners.around(null, POS, Structures.CAPTURE_RADIUS_CHUNKS), "一个回答者都没有");
        assertEquals(Whose.NATURE, whoseBlock());
        withBlockOwners(Map.of("gt_silent", (level, center, chunkRadius) -> null), () ->
                assertSame(Owners.Blocks.NONE, Owners.around(null, POS, Structures.CAPTURE_RADIUS_CHUNKS),
                        "回答者说这一片里它什么都不知道"));
        withBlockOwners(Map.of("gt_elsewhere", saying(STRANGER_ID)), () ->
                assertEquals(Whose.NATURE, Whose.ofBlock(POS.above(), new Facts(null, null, HER, Structures.NONE,
                        Owners.around(null, POS, Structures.CAPTURE_RADIUS_CHUNKS), Map.of())), "回答者没说到的那一格"));
    }

    @Test
    void thePersonAnAnswererNamesIsHerselfTheOwnersSideOrSomeoneElse() {
        withBlockOwners(Map.of("gt_claims", saying(HER_ID)), () -> assertEquals(Whose.SELF, whoseBlock()));
        withBlockOwners(Map.of("gt_claims", saying(OWNER_ID)), () -> assertEquals(Whose.OWNER, whoseBlock()));
        withBlockOwners(Map.of("gt_claims", saying(SISTER_ID)), () ->
                assertEquals(Whose.OWNER, whoseBlock(), "主人名下别的同伴的算主人这一边"));
        withBlockOwners(Map.of("gt_claims", saying(STRANGER_ID)), () -> assertEquals(Whose.OTHER, whoseBlock()));
    }

    @Test
    void answerersWhoDisagreeAreSettledByTheStrictestWhoeverRegisteredFirst() {
        withBlockOwners(two(HER_ID, OWNER_ID), () -> assertEquals(Whose.OWNER, whoseBlock()));
        withBlockOwners(two(OWNER_ID, HER_ID), () -> assertEquals(Whose.OWNER, whoseBlock()));
        withBlockOwners(two(OWNER_ID, STRANGER_ID), () -> assertEquals(Whose.OTHER, whoseBlock()));
        withBlockOwners(two(STRANGER_ID, OWNER_ID), () -> assertEquals(Whose.OTHER, whoseBlock()));
        withBlockOwners(two(HER_ID, STRANGER_ID), () -> assertEquals(Whose.OTHER, whoseBlock()));
        withBlockOwners(two(STRANGER_ID, HER_ID), () -> assertEquals(Whose.OTHER, whoseBlock()));
    }

    @Test
    void anAnswererThatThrowsLosesOnlyItsOwnAnswer() {
        Map<String, BlockOwner> capturing = new LinkedHashMap<>();
        capturing.put("gt_broken", (level, center, chunkRadius) -> {
            throw new IllegalStateException("a broken answerer");
        });
        capturing.put("gt_claims", saying(OWNER_ID));
        withBlockOwners(capturing, () -> assertEquals(Whose.OWNER, whoseBlock(), "取查表时抛的:别人的回答照常"));

        Map<String, BlockOwner> reading = new LinkedHashMap<>();
        reading.put("gt_broken", (level, center, chunkRadius) -> pos -> {
            throw new IllegalStateException("a broken lookup");
        });
        reading.put("gt_claims", saying(OWNER_ID));
        withBlockOwners(reading, () -> {
            Owners.Blocks owners = Owners.around(null, POS, Structures.CAPTURE_RADIUS_CHUNKS);
            assertEquals(List.of(OWNER_ID), owners.at(POS), "读查表时抛的:别人的回答照常");
            assertEquals(List.of(OWNER_ID), owners.at(POS), "坏掉的那一份此后不再问");
        });
    }

    @Test
    void anEntityIsAskedAboutTheSameWayAndAThrowingAnswererIsLeftOut() {
        Facts facts = new Facts(null, null, HER, Structures.NONE, Owners.Blocks.NONE, Map.of());
        assertTrue(Owners.of(null).isEmpty(), "没人回答");
        assertEquals(Whose.NATURE, Whose.ofEntity(null, facts), "没人回答的实体没有主人");

        Map<String, EntityOwner> answerers = new LinkedHashMap<>();
        answerers.put("gt_broken", entity -> {
            throw new IllegalStateException("a broken answerer");
        });
        answerers.put("gt_silent", entity -> null);
        answerers.put("gt_hers", entity -> HER_ID);
        answerers.put("gt_owners", entity -> OWNER_ID);
        withEntityOwners(answerers, () -> {
            assertEquals(List.of(HER_ID, OWNER_ID), Owners.of(null), "各人说的合在一起,没说话的与抛异常的不算");
            assertEquals(Whose.OWNER, Whose.ofEntity(null, facts), "说法不一取最严的");
        });
        withEntityOwners(Map.of("gt_strangers", entity -> STRANGER_ID), () ->
                assertEquals(Whose.OTHER, Whose.ofEntity(null, facts)));
    }
}
