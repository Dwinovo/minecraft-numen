package com.dwinovo.numen.api.client.screen;

import com.dwinovo.numen.api.mind.Bound;
import com.dwinovo.numen.api.mind.CompanionState;
import com.dwinovo.numen.api.mind.Slot;
import com.dwinovo.numen.api.client.screen.SetupDraft.Item;
import com.dwinovo.numen.api.client.skin.SkinChoices;
import com.dwinovo.numen.api.entity.SummonSetup;
import com.dwinovo.numen.api.library.EntryId;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 配置草稿:基线是什么、改没改过、恢复,以及召唤请求里只带改过的项。 */
class SetupDraftTest {

    private static final byte[] PNG = {9, 8, 7};
    private static final UUID ME = UUID.randomUUID();
    private static final UUID FRIEND = UUID.randomUUID();

    /** 我库里的这一条,由我来绑。 */
    private static Bound mine(String id) {
        return new Bound(ME, EntryId.parse(id));
    }

    private static CompanionState.Binding boundBy(UUID who, String name, String id) {
        return new CompanionState.Binding(new Bound(who, EntryId.parse(id)), name);
    }

    private static SkinChoices.Pick pick(SkinChoices.Where where, String name) {
        return new SkinChoices.Pick(new SkinChoices.Choice(where, name, PNG, "hash", "classic", null), "slim");
    }

    @Test
    void theSummonBaselineIsWhatTheServerDoesWhenNothingIsWritten() {
        SetupDraft draft = SetupDraft.summonDefaults();
        assertNull(draft.base(Item.PERSONA), "人设不绑");
        assertNull(draft.base(Item.PROVIDER), "模型配置不绑");
        assertNull(draft.base(Item.VOICE), "声线不绑");
        assertEquals(Boolean.FALSE, draft.base(Item.MODE), "第一次出生是生存");
        assertNull(draft.base(Item.SKIN), "默认皮肤");
        assertEquals(List.of(), draft.changes());
        assertEquals(SummonSetup.NONE, draft.summonSetup(), "什么都没改就什么都不带");
    }

    @Test
    void pickingTheBaselineAgainIsNoChange() {
        SetupDraft draft = SetupDraft.summonDefaults();
        draft.set(Item.MODE, true);
        assertTrue(draft.changed(Item.MODE));
        draft.set(Item.MODE, false);
        assertFalse(draft.changed(Item.MODE), "选回默认那一项就是没改");
        draft.set(Item.PERSONA, mine("my:小焰"));
        draft.set(Item.PERSONA, null);
        assertEquals(List.of(), draft.changes());
    }

    @Test
    void resetGoesBackToTheBaseline() {
        SetupDraft draft = SetupDraft.current(boundBy(ME, "我", "my:小焰"), boundBy(ME, "我", "server:deepseek"), null, true);
        draft.set(Item.PROVIDER, mine("my:local"));
        draft.set(Item.VOICE, mine("my:alex"));
        assertEquals(List.of(Item.PROVIDER, Item.VOICE), draft.changes());
        draft.reset(Item.PROVIDER);
        assertEquals(mine("server:deepseek"), draft.value(Item.PROVIDER));
        assertEquals(List.of(Item.VOICE), draft.changes());
    }

    @Test
    void anotherOwnersBindingIsNotMyEntryOfTheSameName() {
        SetupDraft draft = SetupDraft.current(null, boundBy(FRIEND, "小柚", "server:deepseek"), boundBy(ME, "我", "my:alex"), false);
        assertEquals("小柚", draft.baseBy(Item.PROVIDER), "基线记着是谁绑的");
        assertNull(draft.baseBy(Item.PERSONA), "没绑的没有绑的人");

        assertFalse(mine("server:deepseek").equals(draft.base(Item.PROVIDER)), "同一个 id,朋友绑的和我绑的是两个值");
        draft.set(Item.PROVIDER, mine("server:deepseek"));
        assertEquals(List.of(Item.PROVIDER), draft.changes(), "换成我自己的那一条算改过:要发给服务端,由我来绑");
        draft.reset(Item.PROVIDER);
        assertEquals(List.of(), draft.changes(), "撤销回到朋友绑的那一条");

        draft.set(Item.VOICE, mine("my:alex"));
        assertEquals(List.of(), draft.changes(), "我绑的再选一次是没改");
    }

    @Test
    void theSummonRequestCarriesOnlyWhatChanged() {
        SetupDraft draft = SetupDraft.summonDefaults();
        draft.set(Item.PROVIDER, mine("server:deepseek"));
        draft.set(Item.MODE, true);
        SummonSetup setup = draft.summonSetup();
        assertEquals(Map.of(Slot.PROVIDER, "server:deepseek"), draft.bindings());
        assertEquals(Boolean.TRUE, setup.creative());
        assertNull(setup.skin());
    }

    @Test
    void aSkinFromThisMachineGoesWithItsImageAServerOneByName() {
        SetupDraft draft = SetupDraft.summonDefaults();
        draft.set(Item.SKIN, pick(SkinChoices.Where.MINE, "blue"));
        SummonSetup.Skin mine = draft.summonSetup().skin();
        assertFalse(mine.serverFile());
        assertEquals("blue", mine.name());
        assertArrayEquals(PNG, mine.png());
        assertEquals("slim", mine.variant(), "手臂是选皮肤卡上定的那一种");

        draft.set(Item.SKIN, pick(SkinChoices.Where.SERVER, "knight"));
        SummonSetup.Skin server = draft.summonSetup().skin();
        assertTrue(server.serverFile());
        assertEquals("knight", server.name());
        assertEquals(0, server.png().length, "服务器上的图不用再传回去");
    }
}
