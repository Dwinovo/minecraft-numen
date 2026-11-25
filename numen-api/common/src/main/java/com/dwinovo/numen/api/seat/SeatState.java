package com.dwinovo.numen.api.seat;

import com.google.gson.JsonObject;

/**
 * 她此刻的样子,服务端读身体时在一处写成:给模型的 {@code <runtime_state>} 文字、循环要的手上那件活的结构、
 * 此刻主世界的游戏刻与时刻。只跟着程序结局({@link ProgramResult})与 {@code state/get} 的答复走;事件只带时间。
 *
 * @param text     {@code <runtime_state>},没有可说的是空串
 * @param task     手上的后台活;闲着是 {@code null}
 * @param gameTime 主世界的游戏刻
 * @param dayTime  主世界的时刻({@code dayTime}),条目盖日期钟点用
 */
public record SeatState(String text, BodyTask task, long gameTime, long dayTime) {

    JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("state", text);
        if (task != null) {
            o.add("task", task.toJson());
        }
        o.addProperty("game_time", gameTime);
        o.addProperty("day_time", dayTime);
        return o;
    }

    static SeatState fromJson(JsonObject o) {
        return new SeatState(o.get("state").getAsString(),
                o.has("task") ? BodyTask.fromJson(o.getAsJsonObject("task")) : null,
                o.get("game_time").getAsLong(), o.get("day_time").getAsLong());
    }
}
