package com.dwinovo.numen.client.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** token 数的分档格式:小数只在数字短的时候给。 */
class TokenFormatTest {

    @Test
    void underAThousandIsExact() {
        assertEquals("0", TokenFormat.tokens(0));
        assertEquals("999", TokenFormat.tokens(999));
    }

    @Test
    void thousandsGetOneDecimal() {
        assertEquals("1.0k", TokenFormat.tokens(1_000));
        assertEquals("1.2k", TokenFormat.tokens(1_234));
        assertEquals("9.9k", TokenFormat.tokens(9_949));
    }

    @Test
    void tensOfThousandsDropTheDecimal() {
        // 到这个量级小数位没有信息量,只会挤宽页脚
        assertEquals("10k", TokenFormat.tokens(10_000));
        assertEquals("132k", TokenFormat.tokens(132_400));
        assertEquals("1000k", TokenFormat.tokens(999_600));
    }

    @Test
    void millionsGetOneDecimalAgainThenDropIt() {
        assertEquals("1.2M", TokenFormat.tokens(1_200_000));
        assertEquals("9.9M", TokenFormat.tokens(9_949_000));
        assertEquals("12M", TokenFormat.tokens(12_000_000));
    }

    @Test
    void percentKeepsOneDecimal() {
        assertEquals("87.3", TokenFormat.percent1(0.873));
        assertEquals("100.0", TokenFormat.percent1(1.0));
        assertEquals("0.0", TokenFormat.percent1(0));
    }
}
