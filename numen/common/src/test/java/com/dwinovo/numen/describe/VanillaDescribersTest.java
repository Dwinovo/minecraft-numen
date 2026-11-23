package com.dwinovo.numen.describe;

import com.dwinovo.numen.tools.Signs;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 告示牌的字写成给模型读的几行:两面各四行都在(空行也占位),涂了蜡多一句。炉子的进度写成的几行:燃料还烧几秒、那一件烧到
 * 百分之几,是零的不写。
 */
class VanillaDescribersTest {

    @Test
    void bothSidesKeepTheirFourLines() {
        assertEquals(List.of("sign front: \"Home\", \"sweet home\", \"\", \"\"", "sign back: \"\", \"\", \"\", \"\""),
                VanillaDescribers.signLines(new Signs.Text(List.of("Home", "sweet home", "", ""),
                        List.of("", "", "", ""), false)));
    }

    @Test
    void aWaxedSignSaysItCannotBeEdited() {
        assertEquals("sign is waxed: its text can no longer be edited",
                VanillaDescribers.signLines(new Signs.Text(List.of("", "", "", ""), List.of("", "", "", ""), true))
                        .get(2));
    }

    @Test
    void aLitFurnaceSaysHowLongItsFuelLastsAndHowFarTheItemIs() {
        assertEquals(List.of("burning: the fuel being burned lasts 12 s more", "cooking: 40% done, 6 s left"),
                VanillaDescribers.furnaceLines(240, 80, 200));
        assertEquals(List.of("burning: the fuel being burned lasts 1 s more"), VanillaDescribers.furnaceLines(5, 0, 200),
                "不满一秒往上取整,没在烧东西就没有第二行");
    }

    @Test
    void anIdleFurnaceSaysNothingAndOneWithoutFireSaysTheItemIsPaused() {
        assertEquals(List.of(), VanillaDescribers.furnaceLines(0, 0, 200));
        assertEquals(List.of(), VanillaDescribers.furnaceLines(0, 0, 0));
        assertEquals(List.of("cooking: 50% done, paused: no fuel is burning"),
                VanillaDescribers.furnaceLines(0, 100, 200));
    }
}
