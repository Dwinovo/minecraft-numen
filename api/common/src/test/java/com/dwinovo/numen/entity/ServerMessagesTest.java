package com.dwinovo.numen.entity;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 服务端对她说的话:聊天栏头一回当场交、同一句刷屏折成遍数;动作栏是一格,最新的为准,一个窗口最多交一次。 */
class ServerMessagesTest {

    private record Told(String text, boolean overlay, int repeats) {}

    private final List<Told> told = new ArrayList<>();
    private final ServerMessages messages = new ServerMessages((text, overlay, repeats) ->
            told.add(new Told(text, overlay, repeats)));

    private static final int W = ServerMessages.WINDOW_TICKS;

    // ---- 聊天栏 ----

    @Test
    void aChatLineSaidOnceIsToldOnce() {
        messages.heard("Respawn point set", false, 0);
        messages.tick(W);
        messages.tick(2L * W);
        assertEquals(List.of(new Told("Respawn point set", false, 0)), told);
    }

    @Test
    void aChatLineSaidAgainInsideTheWindowIsCountedAndToldWhenTheWindowEnds() {
        messages.heard("Too far from home", false, 0);
        messages.heard("Too far from home", false, 20);
        messages.heard("Too far from home", false, 40);
        messages.tick(W - 1);
        assertEquals(List.of(new Told("Too far from home", false, 0)), told, "窗口没到,只交了头一回");
        messages.tick(W);
        assertEquals(new Told("Too far from home", false, 2), told.get(1), "窗口到了,交一条带遍数的");
    }

    @Test
    void aChatLineThatKeepsComingIsToldOncePerWindow() {
        for (long t = 0; t <= 3L * W; t += 20) {
            messages.heard("Too far from home", false, t);
            messages.tick(t);
        }
        assertEquals(4, told.size(), "头一回一条,之后每个窗口一条:" + told);
        assertEquals(0, told.get(0).repeats());
        for (Told later : told.subList(1, told.size())) {
            assertEquals(W / 20, later.repeats(), "每个窗口里又说的遍数");
        }
    }

    @Test
    void aChatLineGoneQuietIsForgottenAndCountsAsNewNextTime() {
        messages.heard("Respawn point set", false, 0);
        messages.tick(W);
        messages.heard("Respawn point set", false, W + 5);
        assertEquals(List.of(new Told("Respawn point set", false, 0), new Told("Respawn point set", false, 0)), told);
    }

    @Test
    void differentChatLinesFoldApart() {
        messages.heard("Hello", false, 0);
        messages.heard("Bye", false, 2);
        assertEquals(2, told.size(), "不同的话各是头一回:" + told);
    }

    // ---- 动作栏 ----

    @Test
    void theFirstBarLineAfterQuietIsToldAtOnce() {
        messages.heard("You can sleep only at night", true, 100);
        assertEquals(List.of(new Told("You can sleep only at night", true, 0)), told);
    }

    @Test
    void aBarUsedAsAStatusLineIsToldAtMostOncePerWindowAndTheLatestWins() {
        for (long t = 0; t <= 3L * W; t++) {
            messages.heard("Mana " + t, true, t);
            messages.tick(t);
        }
        assertEquals(List.of(new Told("Mana 0", true, 0), new Told("Mana " + W, true, 0),
                new Told("Mana " + 2 * W, true, 0), new Told("Mana " + 3 * W, true, 0)), told,
                "每刻一句新数值,每个窗口只交一条,交的是那一刻最新的");
    }

    @Test
    void aBarLineStillShowingIsNotToldAgain() {
        for (long t = 0; t <= 3L * W; t += 20) {
            messages.heard("Too far from home", true, t);
            messages.tick(t);
        }
        assertEquals(List.of(new Told("Too far from home", true, 0)), told, "一直挂着的同一句只交一次");
    }

    @Test
    void aNewerBarLineReplacesTheOneNotYetTold() {
        messages.heard("A", true, 0);
        messages.heard("B", true, 10);
        messages.heard("C", true, 20);
        messages.tick(W - 1);
        assertEquals(1, told.size(), "窗口没到,B、C 都等着");
        messages.tick(W);
        assertEquals(List.of(new Told("A", true, 0), new Told("C", true, 0)), told, "窗口到了只交最新的 C,B 被盖掉了");
    }

    @Test
    void theSameBarLineAfterTheBarWentQuietIsToldAgain() {
        messages.heard("You can sleep only at night", true, 0);
        messages.heard("You can sleep only at night", true, 2L * W);
        assertEquals(2, told.size(), "空了一个窗口以后再说,是又说了一遍:" + told);
    }

    @Test
    void anEmptyLineIsNotSpeech() {
        messages.heard("", true, 0);
        messages.heard("   ", false, 1);
        messages.tick(W);
        assertEquals(List.of(), told, "清空动作栏的空文本不交");
    }
}
