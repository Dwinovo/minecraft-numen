package com.dwinovo.numen.tools.inventory;

import com.dwinovo.numen.script.ApiError;
import com.dwinovo.numen.script.ErrorKind;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@code numen.creative.give} 凭空取物:生存画像下当场拒绝(取物之前就抛,背包不动),说该去挖、合、捡、换;创造下照取。 */
class CreativeApiTest {

    @Test
    void survivalCannotConjureItemsAndSaysHowToGetThem() {
        ApiError refused = CreativeApi.refusal(false, "minecraft:diamond");
        assertEquals(ErrorKind.DENIED, refused.kind());
        assertTrue(refused.getMessage().startsWith("survival mode can't conjure items")
                && refused.getMessage().contains("trade for minecraft:diamond instead"), refused.getMessage());
    }

    @Test
    void freeMaterialsConjure() {
        assertNull(CreativeApi.refusal(true, "minecraft:diamond"));
    }
}
