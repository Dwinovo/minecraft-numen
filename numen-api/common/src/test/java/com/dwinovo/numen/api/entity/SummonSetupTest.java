package com.dwinovo.numen.api.entity;

import com.dwinovo.numen.api.brain.Slot;
import com.dwinovo.numen.api.data.ModLanguageData.Keys;
import com.dwinovo.numen.api.network.payload.SummonRequestPayload;
import com.dwinovo.numen.api.skin.SkinError;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/** 召唤卡一并带上的配置:线上来回不走样;召唤前逐项过编辑时的那几道门,第一项不过的就是拒绝的原因。 */
class SummonSetupTest {

    private static final byte[] PNG = {1, 2, 3, 4};

    private static SummonRequestPayload roundTrip(SummonRequestPayload sent) {
        ByteBuf buf = Unpooled.buffer();
        SummonRequestPayload.STREAM_CODEC.encode(buf, sent);
        SummonRequestPayload back = SummonRequestPayload.STREAM_CODEC.decode(buf);
        assertFalse(buf.isReadable(), "读完不剩字节");
        return back;
    }

    private static Map<Slot, String> entries(Object... pairs) {
        Map<Slot, String> out = new EnumMap<>(Slot.class);
        for (int i = 0; i < pairs.length; i += 2) {
            out.put((Slot) pairs[i], (String) pairs[i + 1]);
        }
        return out;
    }

    @Test
    void nothingWrittenTravelsAsNothing() {
        SummonRequestPayload back = roundTrip(new SummonRequestPayload("Alex_1", SummonSetup.NONE));
        assertEquals("Alex_1", back.name());
        assertEquals(SummonSetup.NONE, back.setup());
        assertNull(back.setup().creative(), "没写模式就是没写,不是生存");
        assertNull(back.setup().skin());
    }

    @Test
    void everyWrittenItemTravelsAsWritten() {
        SummonSetup sent = new SummonSetup(entries(Slot.PERSONA, "my:小焰", Slot.PROVIDER, "server:deepseek",
                Slot.VOICE, "my:alex"), true, new SummonSetup.Skin("steve_blue", false, PNG, "slim"));
        SummonSetup back = roundTrip(new SummonRequestPayload("Alex_1", sent)).setup();
        assertEquals(sent, back);
        assertArrayEquals(PNG, back.skin().png());
        assertEquals("slim", back.skin().variant());
    }

    @Test
    void aServerFileTravelsByNameWithoutAnImage() {
        SummonSetup sent = new SummonSetup(Map.of(), false, new SummonSetup.Skin("knight", true, null, null));
        SummonSetup back = roundTrip(new SummonRequestPayload("Alex_1", sent)).setup();
        assertEquals(Boolean.FALSE, back.creative());
        assertEquals("knight", back.skin().name());
        assertEquals(0, back.skin().png().length);
        assertNull(back.skin().variant(), "手臂没写是看图判断");
    }

    /** 假的世界:记下问过什么,答案由用例定。 */
    private static final class World implements SummonSetup.Checks {
        final List<String> asked = new ArrayList<>();
        String missing;
        boolean creativeAllowed = true;
        SkinError skinProblem;

        @Override
        public boolean entryExists(Slot slot, String entry) {
            asked.add(slot + "=" + entry);
            return !entry.equals(missing);
        }

        @Override
        public boolean mayChooseCreative() {
            asked.add("creative");
            return creativeAllowed;
        }

        @Override
        public byte[] skinImage(SummonSetup.Skin skin) {
            asked.add("skin=" + skin.name());
            if (skinProblem != null) {
                throw skinProblem;
            }
            return skin.serverFile() ? PNG : skin.png();
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
    void anEntryThatIsNotThereRefusesNamingTheItem() {
        World world = new World();
        world.missing = "my:nowhere";
        SummonSetup setup = new SummonSetup(entries(Slot.VOICE, "my:nowhere"), null, null);
        Component refusal = setup.prepare(world, "Alex_1").refusal();
        assertEquals(Keys.NOTICE_SUMMON_ENTRY_MISSING, said(refusal).getKey());
        Object[] args = said(refusal).getArgs();
        assertEquals("Alex_1", args[0]);
        assertEquals(Slot.VOICE.label(), said((Component) args[1]).getKey(), "说的是哪一项");
        assertEquals("my:nowhere", args[2]);
    }

    @Test
    void creativeWithoutTheGateRefuses() {
        World world = new World();
        world.creativeAllowed = false;
        Component refusal = new SummonSetup(Map.of(), true, null).prepare(world, "Alex_1").refusal();
        assertEquals(Keys.NOTICE_SUMMON_CREATIVE_DENIED, said(refusal).getKey());
    }

    @Test
    void survivalNeedsNoGate() {
        World world = new World();
        world.creativeAllowed = false;
        assertNull(new SummonSetup(Map.of(), false, null).prepare(world, "Alex_1").refusal());
        assertFalse(world.asked.contains("creative"), "生存不用过创造的门");
    }

    @Test
    void anUnusableSkinRefusesWithItsReason() {
        World world = new World();
        world.skinProblem = SkinError.of(SkinError.BAD_IMAGE, "not a png");
        Component refusal = new SummonSetup(Map.of(), null, new SummonSetup.Skin("broken", false, PNG, null))
                .prepare(world, "Alex_1").refusal();
        assertEquals(Keys.NOTICE_SUMMON_SKIN_REFUSED, said(refusal).getKey());
        assertEquals("broken", said(refusal).getArgs()[1]);
    }

    @Test
    void theFirstItemThatFailsIsTheReasonInSlotThenModeThenSkinOrder() {
        World world = new World();
        world.missing = "my:p";
        world.creativeAllowed = false;
        SummonSetup setup = new SummonSetup(entries(Slot.PERSONA, "my:p"), true,
                new SummonSetup.Skin("s", false, PNG, null));
        Component refusal = setup.prepare(world, "Alex_1").refusal();
        assertEquals(Keys.NOTICE_SUMMON_ENTRY_MISSING, said(refusal).getKey());
        assertEquals(List.of("PERSONA=my:p"), world.asked, "拒了就不再往下问");
    }

    @Test
    void passingCarriesTheImageReadAtCheckTime() {
        World world = new World();
        SummonSetup setup = new SummonSetup(entries(Slot.PROVIDER, "server:x"), true,
                new SummonSetup.Skin("knight", true, null, "classic"));
        SummonSetup.Prepared prepared = setup.prepare(world, "Alex_1");
        assertNull(prepared.refusal());
        assertSame(setup, prepared.setup());
        assertArrayEquals(PNG, prepared.skinPng(), "服务器文件夹里的图在检查时读出,落下时用同一份");
        assertEquals(List.of("PROVIDER=server:x", "creative", "skin=knight"), world.asked);
    }
}
