package com.dwinovo.numen.api.agent.provider;

import com.dwinovo.numen.api.agent.catalog.ThinkingLevel;
import com.dwinovo.numen.api.agent.catalog.ThinkingTable;

import java.util.ArrayList;
import java.util.List;

/**
 * 在这个型号、这个协议、这套兼容项下,每一档思考<b>实际写成什么</b>——全仓只算这一次。
 * 协议实现写请求体、界面列哪几档、界面给每档配的说明,都只问它,谁也不再自己判断"这一档发不发得出去":
 * 档位表({@link ThinkingTable})说型号能怎么控制,兼容项说这个端点认哪种写法,
 * 两者对不上的档位(例如只写开关的端点收不了 effort、只认 effort 的端点收不了预算)就不列,
 * 玩家选得到的每一档都真会改变请求。
 *
 * <p>只有"自动"的计划,表示这个型号在这个端点上没有可控的思考,界面整栏不出。
 */
public final class ThinkingPlan {

    /** 一档在线上的样子。 */
    public record Wire(Kind kind, String effort, int tokens) {

        public enum Kind {
            /** 什么都不发,交给服务端。 */
            AUTO,
            /** 明确关掉(开关型写 disabled/false;effort 型写 {@code none},此时 {@code effort} 是那个字;Anthropic 写 {@code thinking.type=disabled})。 */
            OFF,
            /** 打开,不带量,用量由厂家定。 */
            ON,
            /** 打开并带 effort 取值({@code effort} 是原样发给厂家的字)。 */
            EFFORT,
            /** 打开并带 token 预算({@code tokens})。 */
            BUDGET
        }

        static final Wire AUTO = new Wire(Kind.AUTO, null, 0);
    }

    /** 可选的一档与它的写法,按展示顺序。 */
    public record Step(ThinkingLevel level, Wire wire) {}

    private final List<Step> steps;

    private ThinkingPlan(List<Step> steps) {
        this.steps = List.copyOf(steps);
    }

    /** 这个型号在这套兼容项下的思考计划。 */
    public static ThinkingPlan of(ThinkingTable table, Compat compat) {
        List<Step> steps = new ArrayList<>();
        steps.add(new Step(ThinkingLevel.AUTO, Wire.AUTO));
        if (table.levels().size() > 1) {
            switch (compat) {
                case ChatCompat chat -> chat(table, chat.thinkingFormat(), steps);
                case ResponsesCompat responses -> effortStyle(table, steps);
                case AnthropicCompat anthropic -> anthropic(table, anthropic.adaptiveThinking(), steps);
            }
        }
        return new ThinkingPlan(steps);
    }

    private static void chat(ThinkingTable table, ChatCompat.ThinkingFormat format, List<Step> steps) {
        switch (format) {
            case NONE -> { }
            case OPENAI -> effortStyle(table, steps);
            // 照 pi:对所有推理型号一律写 reasoning.effort,OpenRouter 自己换算成各家的写法(预算也由它算)。
            // 目录给了 effort 取值就用那些,只给预算或开关的型号按缺省给低、中、高;关写 none
            case OPENROUTER -> {
                steps.add(new Step(ThinkingLevel.OFF, new Wire(Wire.Kind.OFF, "none", 0)));
                int before = steps.size();
                for (ThinkingLevel level : table.levels()) {
                    String effort = table.effort(level);
                    if (level != ThinkingLevel.AUTO && level != ThinkingLevel.OFF && effort != null) {
                        steps.add(new Step(level, new Wire(Wire.Kind.EFFORT, effort, 0)));
                    }
                }
                if (steps.size() == before) {
                    for (ThinkingLevel level : List.of(ThinkingLevel.LOW, ThinkingLevel.MEDIUM, ThinkingLevel.HIGH)) {
                        steps.add(new Step(level, new Wire(Wire.Kind.EFFORT, level.id(), 0)));
                    }
                }
            }
            // 只有开关:选再高的档,发出去的也只是"打开"
            case ZAI, QWEN -> {
                offIfOffable(table, steps);
                steps.add(new Step(ThinkingLevel.MEDIUM, new Wire(Wire.Kind.ON, null, 0)));
            }
            // 开关 + 开着时可带 effort:型号给了 effort 就按档发,没给就只有开
            case DEEPSEEK -> {
                offIfOffable(table, steps);
                int before = steps.size();
                for (ThinkingLevel level : table.levels()) {
                    String effort = table.effort(level);
                    if (level != ThinkingLevel.AUTO && level != ThinkingLevel.OFF && effort != null) {
                        steps.add(new Step(level, new Wire(Wire.Kind.EFFORT, effort, 0)));
                    }
                }
                if (steps.size() == before) steps.add(new Step(ThinkingLevel.MEDIUM, new Wire(Wire.Kind.ON, null, 0)));
            }
        }
    }

    /** 只写 effort 取值的端点(Chat 的 openai/openrouter 写法、Responses):没有 effort 取值的档发不出去,不列。 */
    private static void effortStyle(ThinkingTable table, List<Step> steps) {
        for (ThinkingLevel level : table.levels()) {
            String effort = table.effort(level);
            if (level == ThinkingLevel.AUTO || effort == null) continue;
            steps.add(new Step(level,
                    new Wire(level == ThinkingLevel.OFF ? Wire.Kind.OFF : Wire.Kind.EFFORT, effort, 0)));
        }
    }

    private static void anthropic(ThinkingTable table, boolean adaptive, List<Step> steps) {
        // 照 pi:推理型号都有"关",显式写 thinking.type=disabled;自动才是什么都不发
        steps.add(new Step(ThinkingLevel.OFF, new Wire(Wire.Kind.OFF, null, 0)));
        for (ThinkingLevel level : table.levels()) {
            if (level == ThinkingLevel.AUTO || level == ThinkingLevel.OFF) continue;
            if (adaptive) {
                String effort = table.effort(level);
                if (effort != null) steps.add(new Step(level, new Wire(Wire.Kind.EFFORT, effort, 0)));
            } else {
                // 预算是输出的一部分:不小于型号输出上限的预算发出去必被拒
                int tokens = table.budgetTokens(level);
                if (table.maxOutput() <= 0 || tokens < table.maxOutput()) {
                    steps.add(new Step(level, new Wire(Wire.Kind.BUDGET, null, tokens)));
                }
            }
        }
    }

    private static void offIfOffable(ThinkingTable table, List<Step> steps) {
        if (table.levels().contains(ThinkingLevel.OFF)) {
            steps.add(new Step(ThinkingLevel.OFF, new Wire(Wire.Kind.OFF, null, 0)));
        }
    }

    /** 可选的档位,第一个永远是 AUTO。 */
    public List<Step> steps() {
        return steps;
    }

    /** 只有自动:这个型号在这个端点上没有可控的思考。 */
    public boolean autoOnly() {
        return steps.size() == 1;
    }

    /** 玩家选的档位在这个计划里生效的那一档:列表里没有的(型号换了、端点换了)按 AUTO。 */
    public ThinkingLevel effective(ThinkingLevel requested) {
        for (Step s : steps) {
            if (s.level() == requested) return requested;
        }
        return ThinkingLevel.AUTO;
    }

    /** 玩家选的档位真要写成什么;列表里没有的按 AUTO(什么都不发)。 */
    public Wire wireFor(ThinkingLevel requested) {
        for (Step s : steps) {
            if (s.level() == requested) return s.wire();
        }
        return Wire.AUTO;
    }
}
