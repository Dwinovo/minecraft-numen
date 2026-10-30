package com.dwinovo.numen.pathing.benchmark;

import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * 评测结果的一行:一道题在一种规格下走一遍的全部指标。{@code results.jsonl} 的一行就是它;{@link Bench} 写、{@link Report} 与
 * {@link Diff} 读,指标只在这里定义一次。时间都是按表折算的毫秒(不是墙钟),所以同一份代码、同一批题,跑几遍这一行逐字节一致。
 *
 * @param outcome    结局的四类({@link Outcome})
 * @param refStatus  标准答案的状态;数据集里没有答案文件为 null
 * @param ended      怎么收场的细节:{@code arrived},或 {@code searched:<停因>}、{@code blocked} 等
 * @param firstMoveMs 起步耗时:下令到交出第一段
 * @param maxThinkMs 单次最长思考
 * @param waitedMs   身体站着等搜索的总时间
 * @param tripMs     整趟(虚拟身体走完)的耗时;证明到不了的题是证出来用了多久,放弃的是放弃用了多久
 * @param deadEnds   接续证明终点是死路、扔掉路线重搜的次数
 * @param cost       虚拟身体走过的路线价
 * @param subopt     路线价除以最优价:到了、而且标准答案有价钱时才有,否则 NaN
 */
record Row(String id, String region, Problem.Bucket bucket, Spec spec, Problem.GoalType goal, Problem.EndClass endClass, Problem.Split split,
           List<Problem.Tag> tags, Outcome outcome, Answers.Status refStatus, String ended, double firstMoveMs, double maxThinkMs, double waitedMs,
           double tripMs, int searches, int expanded, int deadEnds, int replans, double cost, double subopt, int dug, int placed, int steps) {

    /**
     * 一趟怎么收的,三类:
     * <ul>
     *   <li>{@code ARRIVED}:到了;</li>
     *   <li>{@code UNREACHABLE}:证明到不了。一次搜索把能走到的都搜完了,没有目标({@code Stop.EXHAUSTED})。搜索只要读到快照之外没加载的列
     *       (区域边缘、快照半径的边),停因就是 {@code UNLOADED} 而不是 {@code EXHAUSTED},所以这一类里没有"被区域边缘截断"的;</li>
     *   <li>{@code BEYOND}:要区域之外的地形。没到,并且有一次搜索停在"没加载"、它的快照窗口伸出了区域;区域之外在评测里永远不加载,
     *       游戏里区块随身体走动加载,不会有这种停法,所以不是规划的失败,不进成功率。数据集有标准答案时,答案说区域内能到(最优、已知最好)
     *       或证明区域内无解的,不算这一类,仍是放弃——不让它盖住真正的失败;</li>
     *   <li>{@code GAVE_UP}:放弃,别的一切——总预算用完、重搜之后才搜完、执行里走不下去……</li>
     * </ul>
     */
    enum Outcome { ARRIVED, UNREACHABLE, BEYOND, GAVE_UP }

    boolean arrived() {
        return outcome == Outcome.ARRIVED;
    }

    /** 这一行进成功率的分母:到了或放弃(证明到不了的、要区域之外地形的不是规划的失败)。 */
    boolean attempted() {
        return outcome == Outcome.ARRIVED || outcome == Outcome.GAVE_UP;
    }

    /** 这一行进次优比:到了、标准答案是最优价。已知最好的只是上界,算进去会让次优比偏小。 */
    boolean optimal() {
        return arrived() && refStatus == Answers.Status.OPTIMAL;
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
        o.addProperty("outcome", outcome.name().toLowerCase().replace('_', '-'));
        if (refStatus != null) {
            o.addProperty("refStatus", refStatus.name().toLowerCase().replace('_', '-'));
        }
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
        return o;
    }

    static Row fromJson(JsonObject o) {
        List<Problem.Tag> tags = new ArrayList<>();
        o.getAsJsonArray("tags").forEach(t -> tags.add(Problem.Tag.named(t.getAsString())));
        return new Row(o.get("id").getAsString(), o.get("region").getAsString(), Problem.Bucket.named(o.get("bucket").getAsString()),
                Spec.valueOf(o.get("spec").getAsString()), Problem.GoalType.valueOf(o.get("goal").getAsString().toUpperCase()),
                Problem.EndClass.valueOf(o.get("endClass").getAsString().toUpperCase()), Problem.Split.valueOf(o.get("split").getAsString().toUpperCase()),
                tags, Outcome.valueOf(o.get("outcome").getAsString().toUpperCase().replace('-', '_')),
                o.has("refStatus") ? Answers.Status.valueOf(o.get("refStatus").getAsString().toUpperCase().replace('-', '_')) : null,
                o.get("ended").getAsString(), o.get("firstMoveMs").getAsDouble(), o.get("maxThinkMs").getAsDouble(), o.get("waitedMs").getAsDouble(),
                o.get("tripMs").getAsDouble(), o.get("searches").getAsInt(), o.get("expanded").getAsInt(), o.get("deadEnds").getAsInt(),
                o.get("replans").getAsInt(), o.get("cost").getAsDouble(), o.has("subopt") ? o.get("subopt").getAsDouble() : Double.NaN,
                o.get("dug").getAsInt(), o.get("placed").getAsInt(), o.get("steps").getAsInt());
    }
}
