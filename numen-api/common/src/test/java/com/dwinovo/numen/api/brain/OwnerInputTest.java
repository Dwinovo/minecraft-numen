package com.dwinovo.numen.api.brain;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** 上行的东西不可信:写出去的读得回来,读不懂的返回 null 而不是抛。 */
class OwnerInputTest {

    private static final UUID HER = UUID.randomUUID();

    private static JsonObject json(String text) {
        return JsonParser.parseString(text).getAsJsonObject();
    }

    @Test
    void everyKindSurvivesTheWire() {
        List<OwnerInput> all = List.of(new OwnerInput.Say("G", "你好"), new OwnerInput.Command(HER, "/build 木屋"),
                new OwnerInput.Stop("G"), new OwnerInput.Compact(HER), new OwnerInput.Clear(HER),
                new OwnerInput.SetGoal(HER, "挖 64 个铁", "/goal 挖 64 个铁"), new OwnerInput.ClearGoal(HER),
                new OwnerInput.Bind(HER, Slot.PROVIDER, "server:prov1"), new OwnerInput.Bind(HER, Slot.PROVIDER, null),
                new OwnerInput.Bind(HER, Slot.PERSONA, "my:小焰"), new OwnerInput.Bind(HER, Slot.VOICE, "my:派蒙"),
                new OwnerInput.Bind(HER, Slot.SKIN, null), new OwnerInput.Export(HER), new OwnerInput.Wipe(HER),
                new OwnerInput.Speaking(HER, true),
                new OwnerInput.Pull("G", HER, "N"), new OwnerInput.Pull("G", HER, null),
                new OwnerInput.Emit(HER, "query", "来一下"), new OwnerInput.Drop("G", HER), new OwnerInput.Rename("G", "挖矿队"),
                new OwnerInput.Dissolve("G"));

        for (OwnerInput input : all) {
            assertEquals(input, OwnerInput.fromJson(json(input.toJson().toString())), input.toString());
        }
    }

    @Test
    void anUnreadableInputIsNullNotAnException() {
        assertNull(OwnerInput.fromJson(json("{}")), "没有种类");
        assertNull(OwnerInput.fromJson(json("{\"kind\":\"fly\"}")), "不认识的种类");
        assertNull(OwnerInput.fromJson(json("{\"kind\":\"compact\"}")), "缺同伴");
        assertNull(OwnerInput.fromJson(json("{\"kind\":\"compact\",\"companion\":\"not-a-uuid\"}")), "UUID 写坏了");
        assertNull(OwnerInput.fromJson(json("{\"kind\":\"speaking\",\"companion\":\"" + HER + "\"}")), "缺布尔");
    }
}
