package com.dwinovo.numen.network.payload;

import io.netty.buffer.Unpooled;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.item.ItemStack;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 状态包带着插件从身体上读的那段一起过去。丢了它,她走远了、换了维度就不再知道自己戴着什么,
 * 而且不报错。需要 MC 注册表(物品栏的编解码)。
 */
@Tag("mc")
class NumenStatePayloadTest {

    private static final UUID A = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private static boolean booted;

    @BeforeAll
    static void boot() {
        try {
            net.minecraft.SharedConstants.tryDetectVersion();
            net.minecraft.server.Bootstrap.bootStrap();
            booted = true;
        } catch (Throwable t) {
            booted = false;
        }
    }

    private static NumenStatePayload roundTrip(NumenStatePayload p) {
        RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        NumenStatePayload.STREAM_CODEC.encode(buf, p);
        return NumenStatePayload.STREAM_CODEC.decode(buf);
    }

    @Test
    void thePluginBodyStateRidesAlongWithEveryOtherField() {
        assumeTrue(booted);
        NumenStatePayload sent = new NumenStatePayload(A, true, List.of(), List.of(), 17, 2.5f,
                3, ItemStack.EMPTY, List.of(), "boat", 42, "<curios>ring: gold_ring</curios>");

        NumenStatePayload back = roundTrip(sent);

        assertEquals("<curios>ring: gold_ring</curios>", back.bodyState());
        assertEquals(A, back.uuid());
        assertTrue(back.loaded());
        assertEquals(17, back.foodLevel());
        assertEquals(2.5f, back.saturation());
        assertEquals(3, back.selectedSlot());
        assertEquals("boat", back.vehicleType(), "片段排在最后,前面的字段一个不错位");
        assertEquals(42, back.vehicleId());
    }

    @Test
    void anAbsentBodyCarriesNoFragment() {
        assumeTrue(booted);
        assertEquals("", roundTrip(RequestStatePayload.absent(A)).bodyState());
    }
}
