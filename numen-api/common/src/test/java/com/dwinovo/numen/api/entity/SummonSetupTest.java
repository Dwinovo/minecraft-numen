package com.dwinovo.numen.api.entity;

import com.dwinovo.numen.api.data.ModLanguageData.Keys;
import com.dwinovo.numen.api.network.payload.SummonRequestPayload;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/** 召唤卡一并带上的服务端要办的配置:线上来回不走样;召唤前过编辑时的那道门,不过的就是拒绝的原因。 */
class SummonSetupTest {

    private static SummonRequestPayload roundTrip(SummonRequestPayload sent) {
        ByteBuf buf = Unpooled.buffer();
        SummonRequestPayload.STREAM_CODEC.encode(buf, sent);
        SummonRequestPayload back = SummonRequestPayload.STREAM_CODEC.decode(buf);
        assertFalse(buf.isReadable(), "读完不剩字节");
        return back;
    }

    @Test
    void nothingWrittenTravelsAsNothing() {
        SummonRequestPayload back = roundTrip(new SummonRequestPayload("Alex_1", SummonSetup.NONE));
        assertEquals("Alex_1", back.name());
        assertEquals(SummonSetup.NONE, back.setup());
        assertNull(back.setup().creative(), "没写模式就是没写,不是生存");
    }

    @Test
    void aWrittenModeTravelsAsWritten() {
        assertEquals(Boolean.TRUE, roundTrip(new SummonRequestPayload("Alex_1", new SummonSetup(true))).setup().creative());
        assertEquals(Boolean.FALSE, roundTrip(new SummonRequestPayload("Alex_1", new SummonSetup(false))).setup().creative());
    }

    /** 假的世界:记下问过什么,答案由用例定。 */
    private static final class World implements SummonSetup.Checks {
        final List<String> asked = new ArrayList<>();
        boolean creativeAllowed = true;

        @Override
        public boolean mayChooseCreative() {
            asked.add("creative");
            return creativeAllowed;
        }
    }

    private static TranslatableContents said(Component refusal) {
        return (TranslatableContents) refusal.getContents();
    }

    @Test
    void nothingWrittenAsksNothingAndPasses() {
        World world = new World();
        SummonSetup.Prepared prepared = SummonSetup.NONE.prepare(world, "Alex_1");
        assertNull(prepared.refusal());
        assertEquals(List.of(), world.asked, "没写的项不问,服务端照不指定时的规矩办");
    }

    @Test
    void creativeWithoutTheGateRefuses() {
        World world = new World();
        world.creativeAllowed = false;
        Component refusal = new SummonSetup(true).prepare(world, "Alex_1").refusal();
        assertEquals(Keys.NOTICE_SUMMON_CREATIVE_DENIED, said(refusal).getKey());
    }

    @Test
    void survivalNeedsNoGate() {
        World world = new World();
        world.creativeAllowed = false;
        assertNull(new SummonSetup(false).prepare(world, "Alex_1").refusal());
        assertFalse(world.asked.contains("creative"), "生存不用过创造的门");
    }

    @Test
    void passingCarriesTheSetupToBeAppliedWhenSheArrives() {
        World world = new World();
        SummonSetup setup = new SummonSetup(true);
        SummonSetup.Prepared prepared = setup.prepare(world, "Alex_1");
        assertNull(prepared.refusal());
        assertSame(setup, prepared.setup());
        assertEquals(List.of("creative"), world.asked);
    }
}
