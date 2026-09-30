package com.dwinovo.numen.entity;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 服务端对她说的话:头一回当场交,同一句刷屏在窗口里折成遍数。 */
class ServerMessagesTest {

    private record Told(String text, boolean overlay, int repeats) {}

    private final List<Told> told = new ArrayList<>();
    private final ServerMessages messages = new ServerMessages((text, overlay, repeats) ->
            told.add(new Told(text, overlay, repeats)));

    private static final int W = ServerMessages.WINDOW_TICKS;

    @Test
    void aLineSaidOnceIsToldOnce() {
        messages.heard("You can only sleep at night", true, 0);
        messages.tick(W);
        messages.tick(2L * W);
        assertEquals(List.of(new Told("You can only sleep at night", true, 0)), told);
    }

    @Test
    void aLineSaidAgainInsideTheWindowIsCountedAndToldWhenTheWindowEnds() {
        messages.heard("Too far from home", false, 0);
        messages.heard("Too far from home", false, 20);
        messages.heard("Too far from home", false, 40);
        messages.tick(W - 1);
        assertEquals(List.of(new Told("Too far from home", false, 0)), told, "窗口没到,只交了头一回");
        messages.tick(W);
        assertEquals(new Told("Too far from home", false, 2), told.get(1), "窗口到了,交一条带遍数的");
    }

    @Test
    void aLineThatKeepsComingIsToldOncePerWindow() {
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
    void aLineGoneQuietIsForgottenAndCountsAsNewNextTime() {
        messages.heard("Respawn point set", false, 0);
        messages.tick(W);
        messages.heard("Respawn point set", false, W + 5);
        assertEquals(List.of(new Told("Respawn point set", false, 0), new Told("Respawn point set", false, 0)), told);
    }

    @Test
    void differentLinesAndPlacesFoldApart() {
        messages.heard("Hello", false, 0);
        messages.heard("Hello", true, 1);
        messages.heard("Bye", false, 2);
        assertEquals(3, told.size(), "聊天栏与动作栏的同一句、不同的话,各是头一回:" + told);
    }

    @Test
    void anEmptyLineIsNotSpeech() {
        messages.heard("", true, 0);
        messages.heard("   ", true, 1);
        messages.tick(W);
        assertEquals(List.of(), told, "清空动作栏的空文本不交");
    }
}
