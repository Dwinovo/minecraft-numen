package com.dwinovo.numen.api.client.agent;

import com.dwinovo.numen.api.client.agent.ConversationStatus.Facts;
import org.junit.jupiter.api.Test;

import static com.dwinovo.numen.api.client.agent.ConversationStatus.ERROR;
import static com.dwinovo.numen.api.client.agent.ConversationStatus.NEW;
import static com.dwinovo.numen.api.client.agent.ConversationStatus.NONE;
import static com.dwinovo.numen.api.client.agent.ConversationStatus.WAITING;
import static com.dwinovo.numen.api.client.agent.ConversationStatus.WORKING;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** 头像右下角的状态:同一时刻只有一种,按 等你 > 出错 > 有新话 > 在干活 取;不在线与群聊各有各的规矩。 */
class ConversationStatusTest {

    private static Facts solo(boolean waiting, boolean error, boolean unseen, boolean working) {
        return new Facts(false, true, waiting, error, unseen, working);
    }

    @Test
    void nothingHappeningShowsNothing() {
        assertEquals(NONE, ConversationStatus.of(solo(false, false, false, false)));
    }

    @Test
    void eachFactAloneShowsItsOwnStatus() {
        assertEquals(WAITING, ConversationStatus.of(solo(true, false, false, false)));
        assertEquals(ERROR, ConversationStatus.of(solo(false, true, false, false)));
        assertEquals(NEW, ConversationStatus.of(solo(false, false, true, false)));
        assertEquals(WORKING, ConversationStatus.of(solo(false, false, false, true)));
    }

    @Test
    void priorityIsWaitingThenErrorThenNewThenWorking() {
        assertEquals(WAITING, ConversationStatus.of(solo(true, true, true, true)));
        assertEquals(ERROR, ConversationStatus.of(solo(false, true, true, true)));
        assertEquals(NEW, ConversationStatus.of(solo(false, false, true, true)));
    }

    @Test
    void offlineShowsNothingWhateverElseIsTrue() {
        assertEquals(NONE, ConversationStatus.of(new Facts(false, false, true, true, true, true)));
    }

    @Test
    void groupOnlyEverShowsNew() {
        assertEquals(NEW, ConversationStatus.of(new Facts(true, true, true, true, true, true)));
        assertEquals(NONE, ConversationStatus.of(new Facts(true, true, true, true, false, true)));
    }
}
