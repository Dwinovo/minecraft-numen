package com.dwinovo.numen.api.network.payload;

import com.dwinovo.numen.api.entity.Attendance;
import com.dwinovo.numen.api.entity.Keeper;
import io.netty.buffer.Unpooled;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 名册在线上:每行带着她的整张主人表(创建者在前),写出去的读得回来。 */
@Tag("mc")
class CompanionListPayloadTest {

    private static final UUID HER = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID CREATOR = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID FRIEND = UUID.fromString("33333333-3333-3333-3333-333333333333");

    @Test
    void everyRowCarriesHerWholeOwnerTableCreatorFirst() {
        CompanionListPayload sent = new CompanionListPayload("world-1", List.of(
                new CompanionListPayload.Entry(HER, "小焰", true, Attendance.Outside.byServer("挂机"),
                        List.of(new Keeper(CREATOR, "Steve"), new Keeper(FRIEND, ""))),
                new CompanionListPayload.Entry(UUID.randomUUID(), "阿岩", false, Attendance.Outside.NONE,
                        List.of(new Keeper(CREATOR, "Steve")))));

        RegistryFriendlyByteBuf wire = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        CompanionListPayload.STREAM_CODEC.encode(wire, sent);
        CompanionListPayload read = CompanionListPayload.STREAM_CODEC.decode(wire);

        assertEquals(sent, read);
        assertEquals(0, wire.readableBytes(), "读完不该剩字节");
        assertEquals(List.of(new Keeper(CREATOR, "Steve"), new Keeper(FRIEND, "")), read.companions().get(0).keepers(),
                "创建者在前,名字查不到的是空串");
    }
}
