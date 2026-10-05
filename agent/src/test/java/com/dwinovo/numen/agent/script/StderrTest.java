package com.dwinovo.numen.agent.script;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** stderr 一栏:每条带行号与函数名,连续相同的合并,一条与整栏都有界且写明省略了多少。 */
class StderrTest {

    private final Stderr stderr = new Stderr();

    @Test
    void anEntryCarriesItsLineAndFunctionAndNothingToSayWritesNothing() {
        stderr.write(5, "numen.fight.attack", "killed minecraft:cow");
        stderr.write(6, "numen.inv.count", "");
        stderr.write(7, "numen.inv.count", "   ");
        stderr.write(8, "numen.inv.count", null);
        assertEquals("line 5 numen.fight.attack: killed minecraft:cow", stderr.text());
    }

    @Test
    void aWholeAccountIsOneEntryWhoseLaterLinesAreIndented() {
        stderr.write(2, "numen.move.go", "walked there\nbroke 2 stone on the way");
        assertEquals("line 2 numen.move.go: walked there\n  broke 2 stone on the way", stderr.text());
    }

    @Test
    void entriesThatAreTheSameOneAfterAnotherBecomeOneWithACount() {
        for (int i = 0; i < 40; i++) {
            stderr.write(3, "numen.work.dig", "not_found — no block there");
        }
        stderr.write(4, "numen.work.dig", "dug 1 stone");
        stderr.write(3, "numen.work.dig", "not_found — no block there");
        assertEquals("line 3 numen.work.dig: not_found — no block there (×40)\n"
                + "line 4 numen.work.dig: dug 1 stone\n"
                + "line 3 numen.work.dig: not_found — no block there", stderr.text());
    }

    @Test
    void sameWordsOnAnotherLineAreAnotherEntry() {
        stderr.write(1, "numen.work.dig", "dug 1 stone");
        stderr.write(2, "numen.work.dig", "dug 1 stone");
        assertEquals("line 1 numen.work.dig: dug 1 stone\nline 2 numen.work.dig: dug 1 stone", stderr.text());
    }

    @Test
    void aLongEntryKeepsWholeLinesThatFitAndSaysHowMuchWasLeftOut() {
        String line = "x".repeat(99) + "\n";
        stderr.write(1, "f", line.repeat(100));
        String text = stderr.text();
        assertTrue(text.length() < ScriptLimits.STDERR_RECORD_CHARS + 200, text.length() + "");
        assertTrue(text.matches("(?s).*\\[\\d+ more characters of this entry left out]$"), text);
    }

    @Test
    void aSingleLineLongerThanTheLimitIsCutAndSaysSo() {
        stderr.write(1, "f", "y".repeat(ScriptLimits.STDERR_RECORD_CHARS * 2));
        assertTrue(stderr.text().contains("[" + (ScriptLimits.STDERR_RECORD_CHARS * 2 - ScriptLimits.STDERR_RECORD_CHARS)
                + " more characters of this entry left out]"), stderr.text().substring(0, 60));
    }

    @Test
    void theWholeChannelIsBoundedAndSaysHowManyEntriesWereLeftOut() {
        for (int i = 0; i < 100; i++) {
            stderr.write(i + 1, "numen.build.place", "placed " + i + " blocks, " + "z".repeat(480));
        }
        String text = stderr.text();
        assertTrue(text.length() < ScriptLimits.STDERR_CHARS + 200, text.length() + "");
        assertTrue(text.startsWith("line 1 numen.build.place: placed 0 blocks"), "the head is kept");
        assertTrue(text.matches("(?s).*\\n\\[\\d+ more stderr entries \\(\\d+ characters\\) left out: stderr keeps the "
                + "first " + ScriptLimits.STDERR_CHARS + " characters]$"), text.substring(text.length() - 120));
        assertFalse(text.contains("placed 99 blocks"));
    }

    @Test
    void anEmptyChannelHasNothingToShow() {
        assertTrue(stderr.isEmpty());
        assertEquals("", stderr.text());
    }
}
