package com.dwinovo.numen.describe;

import com.dwinovo.numen.tools.Signs;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 告示牌的字写成给模型读的几行:两面各四行都在(空行也占位),涂了蜡多一句。 */
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
}
