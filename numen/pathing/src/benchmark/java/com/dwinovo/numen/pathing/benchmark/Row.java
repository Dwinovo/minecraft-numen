package com.dwinovo.numen.pathing.benchmark;

import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * 评测结果的一行:一道题在一种规格下走一遍的全部指标。{@code results.jsonl} 的一行就是它;{@link Bench} 写、{@link Report} 与
 * {@link Diff} 读,指标只在这里定义一次。时间都是按表折算的毫秒(不是墙钟),{@code wallMs} 是唯一的墙钟字段。
 *
 * @param unsolvable 这种规格下按构造到不了的题({@link Problem#unsolvable}):单独成表,量多快认输
 * @param refStatus  标准答案的状态;数据集里没有答案文件为 null
 * @param ended      怎么收场:{@code arrived},或 {@code searched:<停因>}、{@code blocked} 等
 * @param firstMoveMs 起步耗时:下令到交出第一段
 * @param maxThinkMs 单次最长思考
 * @param waitedMs   身体站着等搜索的总时间
 * @param tripMs     整趟(虚拟身体走完)的耗时;无解的题是认输用了多久
 * @param deadEnds   接续证明终点是死路、扔掉路线重搜的次数
 * @param cost       虚拟身体走过的路线价
 * @param subopt     路线价除以最优价:到了、而且标准答案有价钱时才有,否则 NaN
 * @param wallMs     这一行在工作线程上用的墙钟毫秒
 */
record Row(String id, String region, Problem.Bucket bucket, Spec spec, Problem.GoalType goal, Problem.EndClass endClass, Problem.Split split,
           List<Problem.Tag> tags, boolean unsolvable, Answers.Status refStatus, boolean arrived, String ended, double firstMoveMs, double maxThinkMs, double waitedMs,
           double tripMs, int searches, int expanded, int deadEnds, int replans, double cost, double subopt, int dug, int placed, int steps,
           double wallMs) {

    /** 这一行进次优比:到了、标准答案是最优价。已知最好的只是上界,算进去会让次优比偏小。 */
    boolean optimal() {
        return arrived && refStatus == Answers.Status.OPTIMAL;
    }

    /** 排序与对比用的键:题号加规格。 */
    String key() {
        return id + "|" + spec.name();
    }

    JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("id", id);
        o.addProperty("region", region);
        o.addProperty("bucket", bucket.label);
        o.addProperty("spec", spec.name());
        o.addProperty("goal", goal.name().toLowerCase());
        o.addProperty("endClass", endClass.name().toLowerCase());
        o.addProperty("split", split.name().toLowerCase());
        JsonArray tagArray = new JsonArray();
        tags.forEach(t -> tagArray.add(t.label));
        o.add("tags", tagArray);
        o.addProperty("unsolvable", unsolvable);
        if (refStatus != null) {
            o.addProperty("refStatus", refStatus.name().toLowerCase().replace('_', '-'));
        }
        o.addProperty("arrived", arrived);
        o.addProperty("ended", ended);
        o.addProperty("firstMoveMs", firstMoveMs);
        o.addProperty("maxThinkMs", maxThinkMs);
        o.addProperty("waitedMs", waitedMs);
        o.addProperty("tripMs", tripMs);
        o.addProperty("searches", searches);
        o.addProperty("expanded", expanded);
        o.addProperty("deadEnds", deadEnds);
        o.addProperty("replans", replans);
        o.addProperty("cost", cost);
        if (!Double.isNaN(subopt)) {
            o.addProperty("subopt", subopt);
        }
        o.addProperty("dug", dug);
        o.addProperty("placed", placed);
        o.addProperty("steps", steps);
        o.addProperty("wallMs", wallMs);
        return o;
    }

    static Row fromJson(JsonObject o) {
        List<Problem.Tag> tags = new ArrayList<>();
        o.getAsJsonArray("tags").forEach(t -> tags.add(Problem.Tag.named(t.getAsString())));
        return new Row(o.get("id").getAsString(), o.get("region").getAsString(), Problem.Bucket.named(o.get("bucket").getAsString()),
                Spec.valueOf(o.get("spec").getAsString()), Problem.GoalType.valueOf(o.get("goal").getAsString().toUpperCase()),
                Problem.EndClass.valueOf(o.get("endClass").getAsString().toUpperCase()), Problem.Split.valueOf(o.get("split").getAsString().toUpperCase()),
                tags, o.get("unsolvable").getAsBoolean(),
                o.has("refStatus") ? Answers.Status.valueOf(o.get("refStatus").getAsString().toUpperCase().replace('-', '_')) : null,
                o.get("arrived").getAsBoolean(),
                o.get("ended").getAsString(), o.get("firstMoveMs").getAsDouble(), o.get("maxThinkMs").getAsDouble(), o.get("waitedMs").getAsDouble(),
                o.get("tripMs").getAsDouble(), o.get("searches").getAsInt(), o.get("expanded").getAsInt(), o.get("deadEnds").getAsInt(),
                o.get("replans").getAsInt(), o.get("cost").getAsDouble(), o.has("subopt") ? o.get("subopt").getAsDouble() : Double.NaN,
                o.get("dug").getAsInt(), o.get("placed").getAsInt(), o.get("steps").getAsInt(), o.get("wallMs").getAsDouble());
    }
}
