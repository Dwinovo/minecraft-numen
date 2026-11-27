package com.dwinovo.numen.api.seat;

import com.dwinovo.numen.api.agent.llm.Rows;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * {@link SeatMethods#CONVERSATION_OPEN} 的答复:她当前的会话,和脑子要的东西({@link Rows.Opening})。
 *
 * @param conversation 她此刻在写的会话 id;此后追加的行属于它
 */
public record ConversationOpened(UUID conversation, Rows.Opening opening) {

    JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("conversation", conversation.toString());
        o.add("rows", array(opening.rows()));
        if (opening.last() != null) {
            o.addProperty("last", opening.last());
        }
        o.add("goal", array(opening.goal()));
        return o;
    }

    static ConversationOpened fromJson(JsonObject o) {
        return new ConversationOpened(UUID.fromString(o.get("conversation").getAsString()),
                new Rows.Opening(objects(o.getAsJsonArray("rows")), o.has("last") ? o.get("last").getAsString() : null,
                        objects(o.getAsJsonArray("goal"))));
    }

    static JsonArray array(List<JsonObject> rows) {
        JsonArray a = new JsonArray();
        rows.forEach(a::add);
        return a;
    }

    static List<JsonObject> objects(JsonArray a) {
        List<JsonObject> out = new ArrayList<>();
        a.forEach(e -> out.add(e.getAsJsonObject()));
        return out;
    }
}
