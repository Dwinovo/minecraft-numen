package com.dwinovo.numen.api.seat;

import com.google.gson.JsonObject;

/**
 * {@link SeatMethods#SEAT_OPEN} 的答复:座位坐上了,她此刻的状况。
 *
 * @param generation 她的身体此刻的代数;身体从没进过世界是 0
 * @param catalog    此刻的目录版本;和手里的不同就再取一次
 * @param lastSeq    服务端发过的最后一个序号;一条都没发过是 0
 * @param firstSeq   服务端还留着的最早一个序号;一条都没留是 {@code lastSeq + 1}。比客户端手里的最后一号加一还大,
 *                   中间有被丢掉的(发件箱满了,最老的先丢)
 * @param reset      客户端报来的序号比服务端发过的还大(服务端重启过):客户端的序号作废,留着的事件从头补
 */
public record Opened(long generation, String catalog, long lastSeq, long firstSeq, boolean reset) {

    JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("generation", generation);
        o.addProperty("catalog", catalog);
        o.addProperty("last_seq", lastSeq);
        o.addProperty("first_seq", firstSeq);
        o.addProperty("reset", reset);
        return o;
    }

    static Opened fromJson(JsonObject o) {
        return new Opened(o.get("generation").getAsLong(), o.get("catalog").getAsString(), o.get("last_seq").getAsLong(),
                o.get("first_seq").getAsLong(), o.get("reset").getAsBoolean());
    }
}
