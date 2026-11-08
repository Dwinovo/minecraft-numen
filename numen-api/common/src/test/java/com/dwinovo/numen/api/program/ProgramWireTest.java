package com.dwinovo.numen.api.program;

import com.dwinovo.numen.api.network.Wire;
import com.dwinovo.numen.api.network.payload.ClientCallPayload;
import com.dwinovo.numen.api.network.payload.ClientCallResultPayload;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import java.util.function.Function;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 反向请求用的两个包:服务端问、主人的客户端答,编码再解码是同一个包。 */
@Tag("mc")
class ProgramWireTest {

    private static final UUID A = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private static <T extends CustomPacketPayload> T wire(Function<FriendlyByteBuf, T> decoder, T payload) {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        payload.write(buf);
        assertTrue(buf.readableBytes() > 0);
        T back = decoder.apply(buf);
        assertEquals(0, buf.readableBytes(), "the whole payload was read back");
        return back;
    }

    @Test
    void theReverseRequestAndItsAnswerComeBackTheSame() {
        ClientCallPayload ask = new ClientCallPayload(A, "call_1#2", "gt.gt_mix.twice", "{\"n\":2}");
        assertEquals(ask, wire(ClientCallPayload::read, ask));
        assertTrue(Wire.TO_CLIENT.holds(ask.size()));
        ClientCallResultPayload answer = new ClientCallResultPayload(A, "call_1#2", "{\"ok\":true}");
        assertEquals(answer, wire(ClientCallResultPayload::read, answer));
        assertTrue(Wire.TO_SERVER.holds(answer.size()));
    }
}
