package com.dwinovo.numen.api.talk;

import com.google.gson.JsonObject;

import java.util.UUID;

/**
 * 一句话是谁、在哪儿说的。她的大脑记着此刻在答的那一句的这三样:回话回给那一位、回到他问的地方({@link Venue}),她是为他做事
 * (程序带着他这位 keeper 起跑),她此刻在哪个会话里也是这句话的。会话态,不落盘。服务端把别人的话送到她的座位时带着它
 * ({@code SeatEvent#spoken}),她的回话({@code say})带着它回来。
 *
 * @param who          说话的那位 keeper(创建者或分享给了他的人)
 * @param venue        他是在哪儿说的
 * @param conversation 群里说的话,是会话的印({@code Conversations#tagOf});别处是 {@code null}
 */
public record Spoken(UUID who, Venue venue, String conversation) {

    public Spoken {
        java.util.Objects.requireNonNull(who, "who");
        java.util.Objects.requireNonNull(venue, "venue");
    }

    /** 创建者亲口在面板里说的。 */
    public static Spoken panel(UUID creator) {
        return new Spoken(creator, Venue.PANEL, null);
    }

    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("who", who.toString());
        o.addProperty("venue", venue.name());
        if (conversation != null) {
            o.addProperty("conversation", conversation);
        }
        return o;
    }

    public static Spoken fromJson(JsonObject o) {
        return new Spoken(UUID.fromString(o.get("who").getAsString()), Venue.valueOf(o.get("venue").getAsString()),
                o.has("conversation") ? o.get("conversation").getAsString() : null);
    }
}
