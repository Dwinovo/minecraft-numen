package com.dwinovo.numen.api.client.screen;

import com.dwinovo.numen.api.brain.Slot;
import com.dwinovo.numen.api.client.screen.SetupDraft.Item;
import com.dwinovo.numen.api.client.skin.SkinChoices;
import com.dwinovo.numen.api.entity.SummonSetup;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 配置草稿:基线是什么、改没改过、恢复,以及召唤请求里只带改过的项。 */
class SetupDraftTest {

    private static final byte[] PNG = {9, 8, 7};

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
        draft.set(Item.PERSONA, "my:小焰");
        draft.set(Item.PERSONA, null);
        assertEquals(List.of(), draft.changes());
    }

    @Test
    void resetGoesBackToTheBaseline() {
        SetupDraft draft = SetupDraft.current("my:小焰", "server:deepseek", null, true);
        draft.set(Item.PROVIDER, "my:local");
        draft.set(Item.VOICE, "my:alex");
        assertEquals(List.of(Item.PROVIDER, Item.VOICE), draft.changes());
        draft.reset(Item.PROVIDER);
        assertEquals("server:deepseek", draft.value(Item.PROVIDER));
        assertEquals(List.of(Item.VOICE), draft.changes());
    }

    @Test
    void theSummonRequestCarriesOnlyWhatChanged() {
        SetupDraft draft = SetupDraft.summonDefaults();
        draft.set(Item.PROVIDER, "server:deepseek");
        draft.set(Item.MODE, true);
        SummonSetup setup = draft.summonSetup();
        assertEquals(Map.of(Slot.PROVIDER, "server:deepseek"), setup.entries());
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
