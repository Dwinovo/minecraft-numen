package com.dwinovo.numen.api.seat;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 她手上那件后台活,循环要用的那几样事实(有没有、有没有终点、给主人看的那一句的语言键与词)。和 {@link SeatState#text} 里的
 * {@code <current_task>} 出自服务端同一份任务快照:模型读的文字、循环判断用的结构,不是两处各算的两份。
 *
 * @param id           公开任务 id
 * @param tool         任务名:函数全名
 * @param standing     常驻:没有终点,不会发 task_finished
 * @param activityKey  给主人看的一句的语言键
 * @param activityArgs 那一句的现场的词
 */
public record BodyTask(String id, String tool, boolean standing, String activityKey, List<String> activityArgs) {

    public BodyTask {
        activityArgs = List.copyOf(activityArgs);
    }

    JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("id", id);
        o.addProperty("tool", tool);
        o.addProperty("standing", standing);
        o.addProperty("activity_key", activityKey);
        JsonArray args = new JsonArray();
        activityArgs.forEach(args::add);
        o.add("activity_args", args);
        return o;
    }

    static BodyTask fromJson(JsonObject o) {
        List<String> args = new ArrayList<>();
        o.getAsJsonArray("activity_args").forEach(a -> args.add(a.getAsString()));
        return new BodyTask(o.get("id").getAsString(), o.get("tool").getAsString(), o.get("standing").getAsBoolean(),
                o.get("activity_key").getAsString(), args);
    }
}
