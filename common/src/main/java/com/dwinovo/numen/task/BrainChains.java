package com.dwinovo.numen.task;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;

/**
 * 链登记口:内容包(numen-core、第三方)把自己的竞价链工厂注册进来,引擎的
 * {@link CompanionBrain} 每同伴实例化一份。工厂收一个 {@link BodyLog}(链的
 * 叙事出口),返回链实例;{@code order} 决定同 tick 平局时的先后(小者先,
 * 惯例:意图越硬的越小)。引擎自带的 {@link LlmTaskChain} 与
 * 说话看人姿态链不经此处——它们是机器的一部分,固定排在注册链之后。
 *
 * <p>Init-time only(和 ToolRegistry/CompanionTaskFactory 同一约定):
 * 注册发生在 mod init,Brain 构造发生在服务器 tick——无并发窗口。
 */
public final class BrainChains {

    private record Entry(int order, Function<BodyLog, TaskChain> factory) {}

    private static final List<Entry> ENTRIES = new ArrayList<>();

    private BrainChains() {}

    /** 注册一条链工厂。{@code order} 小者先(平局裁决用,与优先级无关)。 */
    public static synchronized void register(int order, Function<BodyLog, TaskChain> factory) {
        ENTRIES.add(new Entry(order, factory));
    }

    /** 为一个新 Brain 实例化全部注册链(按 order 排序)。 */
    static synchronized List<TaskChain> build(BodyLog bodyLog) {
        List<TaskChain> out = new ArrayList<>();
        ENTRIES.stream()
                .sorted(Comparator.comparingInt(Entry::order))
                .forEach(e -> out.add(e.factory().apply(bodyLog)));
        return out;
    }

    public static synchronized int size() {
        return ENTRIES.size();
    }
}
