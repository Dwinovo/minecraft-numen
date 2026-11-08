package com.dwinovo.numen.api.agent.catalog;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 一个型号的思考档位表:它支持统一档位里的哪几档,每一档在线上写成什么。
 * 由 models.dev 的 {@code reasoning_options} 推出,是"这个型号能怎么控制思考"的唯一出处。
 * 在具体协议、兼容项下哪些档位发得出去、每档写成什么,见 {@code ThinkingPlan}——那是请求体与界面共用的唯一一处。
 *
 * <p>models.dev 描述的控制方式有三种,可以并存:
 * <ul>
 *   <li>{@code effort}(带取值表):线上就是取值表里的字,统一档位按下表认领——
 *       低 = {@code low}(没有就用 {@code minimal}),中 = {@code medium},高 = {@code high},
 *       最高 = {@code max}(没有就用 {@code xhigh});{@code none} 是"关"。表里没有的档位这个型号就没有。</li>
 *   <li>{@code budget_tokens}(可带下限):档位映射成 token 预算,见 {@link #budgetTokens};
 *       思考是选择性开启的,不发就是关。</li>
 *   <li>{@code toggle}:只有开和关。"开"在统一档位里没有位置,记成中档。</li>
 * </ul>
 * 型号不是推理型号,或者推理却没给任何控制方式、或者目录里根本查不到,都只有 {@link ThinkingLevel#AUTO}。
 *
 * @param levels    可选的档位,第一个永远是 AUTO
 * @param efforts   档位 → 线上取值(只含 effort 型给出的;{@code OFF → "none"} 也在这里)
 * @param toggle    是否只有开关(没有 effort 取值表也没有预算)
 * @param budget    是否有预算型控制
 * @param budgetMin 预算下限,没写是 0
 * @param maxOutput 型号的输出上限,0 = 不知道;预算是输出的一部分,不小于它的预算发出去必被拒({@code ThinkingPlan} 据此不列)
 */
public record ThinkingTable(List<ThinkingLevel> levels, Map<ThinkingLevel, String> efforts,
                            boolean toggle, boolean budget, int budgetMin, int maxOutput) {

    /** 查不到能力、不是推理型号、或没有可控的方式。 */
    public static final ThinkingTable AUTO_ONLY =
            new ThinkingTable(List.of(ThinkingLevel.AUTO), Map.of(), false, false, 0, 0);

    /** 预算型的档位 → token 数:全仓唯一的一张表。 */
    private static final Map<ThinkingLevel, Integer> BUDGETS = Map.of(
            ThinkingLevel.LOW, 2_048,
            ThinkingLevel.MEDIUM, 8_192,
            ThinkingLevel.HIGH, 16_384,
            ThinkingLevel.MAX, 32_768);

    /** 每个统一档位认领的 effort 取值,按先后顺序取第一个有的。 */
    private static final Map<ThinkingLevel, List<String>> EFFORT_WORDS = Map.of(
            ThinkingLevel.LOW, List.of("low", "minimal"),
            ThinkingLevel.MEDIUM, List.of("medium"),
            ThinkingLevel.HIGH, List.of("high"),
            ThinkingLevel.MAX, List.of("max", "xhigh"));

    public ThinkingTable {
        levels = List.copyOf(levels);
        efforts = Map.copyOf(efforts);
    }

    /**
     * 从 models.dev 的 {@code reasoning} 与 {@code reasoning_options} 推出。
     *
     * @param options 可为 null(字段缺失)
     */
    public static ThinkingTable parse(boolean reasoning, JsonArray options) {
        return parse(reasoning, options, 0);
    }

    /**
     * 同上,并记下型号的输出上限。
     *
     * @param maxOutput 型号的输出上限;0 = 不知道
     */
    public static ThinkingTable parse(boolean reasoning, JsonArray options, int maxOutput) {
        if (!reasoning || options == null) return AUTO_ONLY;
        List<String> words = new ArrayList<>();
        boolean toggle = false;
        boolean budget = false;
        int budgetMin = 0;
        for (JsonElement el : options) {
            if (!el.isJsonObject()) continue;
            JsonObject o = el.getAsJsonObject();
            String type = o.has("type") ? o.get("type").getAsString() : "";
            switch (type) {
                case "toggle" -> toggle = true;
                case "budget_tokens" -> {
                    budget = true;
                    if (o.has("min") && o.get("min").isJsonPrimitive()) {
                        budgetMin = Math.max(0, o.get("min").getAsInt());
                    }
                }
                case "effort" -> {
                    if (o.has("values") && o.get("values").isJsonArray()) {
                        for (JsonElement v : o.getAsJsonArray("values")) {
                            if (v.isJsonPrimitive()) words.add(v.getAsString());
                        }
                    }
                }
                default -> { }
            }
        }

        Map<ThinkingLevel, String> efforts = new EnumMap<>(ThinkingLevel.class);
        for (var e : EFFORT_WORDS.entrySet()) {
            for (String w : e.getValue()) {
                if (words.contains(w)) {
                    efforts.put(e.getKey(), w);
                    break;
                }
            }
        }
        boolean hasNone = words.contains("none");
        if (hasNone) efforts.put(ThinkingLevel.OFF, "none");

        boolean offable = hasNone || toggle || budget;
        List<ThinkingLevel> levels = new ArrayList<>();
        levels.add(ThinkingLevel.AUTO);
        if (efforts.keySet().stream().anyMatch(l -> l != ThinkingLevel.OFF)) {
            if (offable) levels.add(ThinkingLevel.OFF);
            for (ThinkingLevel l : EFFORT_WORDS.keySet().stream().sorted().toList()) {
                if (efforts.containsKey(l)) levels.add(l);
            }
            return new ThinkingTable(levels, efforts, false, budget, budgetMin, maxOutput);
        }
        if (budget) {
            levels.addAll(List.of(ThinkingLevel.OFF, ThinkingLevel.LOW, ThinkingLevel.MEDIUM,
                    ThinkingLevel.HIGH, ThinkingLevel.MAX));
            return new ThinkingTable(levels, efforts, false, true, budgetMin, maxOutput);
        }
        if (toggle) {
            levels.addAll(List.of(ThinkingLevel.OFF, ThinkingLevel.MEDIUM));
            return new ThinkingTable(levels, efforts, true, false, 0, maxOutput);
        }
        return AUTO_ONLY;
    }

    /** 这一档在线上写成的 effort 取值;没有给 {@code null}。 */
    public String effort(ThinkingLevel level) {
        return efforts.get(level);
    }

    /** 这一档的预算(token),不低于型号下限;低/中/高/最高以外的档位给 0。 */
    public int budgetTokens(ThinkingLevel level) {
        Integer b = BUDGETS.get(level);
        return b == null ? 0 : Math.max(b, budgetMin);
    }

    /** 目录说这个型号会思考,并且给了控制方式(档位不止"自动")。 */
    public boolean thinks() {
        return levels.size() > 1;
    }

    /** 控制方式是"effort 取值表,没有预算":自适应思考的型号是这个形状。 */
    public boolean effortOnly() {
        return !efforts.isEmpty() && !budget;
    }
}
