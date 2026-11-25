package com.dwinovo.numen.api.mind;

import com.dwinovo.numen.api.library.EntryId;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 她的绑定文件读回来和写出去的一样,每一项记着是谁绑的;只有 id 的旧值读进来算创建者绑的,再写出去就是带着绑的人的样子。
 */
class BindingsTest {

    private static final UUID CREATOR = UUID.randomUUID();
    private static final UUID FRIEND = UUID.randomUUID();

    private static JsonObject json(String text) {
        return JsonParser.parseString(text).getAsJsonObject();
    }

    @Test
    void whoBoundEachItemSurvivesTheFile() {
        Bindings written = Bindings.NONE
                .with(Slot.PROVIDER, new Bound(FRIEND, EntryId.mine("档案")))
                .with(Slot.PERSONA, new Bound(CREATOR, new EntryId("numen", "小焰")))
                .wearing("abc-slim");

        JsonObject file = json(written.toJson().toString());

        assertEquals(FRIEND.toString(), file.getAsJsonObject("provider").get("by").getAsString());
        assertEquals("my:档案", file.getAsJsonObject("provider").get("id").getAsString());
        assertEquals("abc-slim", file.get("skin").getAsString(), "皮肤不带出处,还是一个引用");
        assertEquals(written, Bindings.read(file, UUID.randomUUID()), "带着绑的人的值,读的时候不看创建者是谁");
    }

    @Test
    void aValueThatIsOnlyAnIdWasBoundByHerCreatorAndIsWrittenBackWithHim() {
        Bindings read = Bindings.read(json("{\"provider\":\"my:档案\",\"voice\":\"my:派蒙\",\"skin\":\"abc-slim\"}"), CREATOR);

        assertEquals(new Bound(CREATOR, EntryId.mine("档案")), read.bound(Slot.PROVIDER));
        assertEquals(new Bound(CREATOR, EntryId.mine("派蒙")), read.bound(Slot.VOICE));
        assertNull(read.bound(Slot.PERSONA));
        assertEquals("abc-slim", read.skin());
        assertEquals(CREATOR.toString(), read.toJson().getAsJsonObject("voice").get("by").getAsString());
    }

    @Test
    void oldAndNewValuesMayShareAFileAndAnUnreadableOneIsNotBound() {
        Bindings read = Bindings.read(json("{\"provider\":{\"by\":\"" + FRIEND + "\",\"id\":\"my:档案\"},"
                + "\"persona\":\"numen:小焰\",\"voice\":{\"by\":\"谁\",\"id\":\"my:派蒙\"}}"), CREATOR);

        assertEquals(Map.of(Slot.PROVIDER, new Bound(FRIEND, EntryId.mine("档案")),
                Slot.PERSONA, new Bound(CREATOR, new EntryId("numen", "小焰"))), read.slots());
        assertNull(Bindings.read(json("{\"persona\":\"没有命名空间\"}"), CREATOR).bound(Slot.PERSONA));
    }

    @Test
    void whatOnePersonBoundCanBeToldApartFromTheRest() {
        Bindings bindings = Bindings.NONE
                .with(Slot.PROVIDER, new Bound(FRIEND, EntryId.mine("档案")))
                .with(Slot.PERSONA, new Bound(CREATOR, EntryId.mine("小焰")))
                .with(Slot.VOICE, new Bound(FRIEND, EntryId.mine("派蒙")));

        assertEquals(List.of(Slot.PROVIDER, Slot.VOICE), bindings.boundBy(FRIEND));
        assertEquals(List.of(), bindings.boundBy(UUID.randomUUID()));
        assertTrue(bindings.with(Slot.PROVIDER, null).with(Slot.PERSONA, null).with(Slot.VOICE, null).isEmpty(),
                "什么都没绑、没穿皮肤的绑定不留文件");
    }
}
