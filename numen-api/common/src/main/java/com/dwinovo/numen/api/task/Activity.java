package com.dwinovo.numen.api.task;

import net.minecraft.network.chat.Component;

import java.util.Arrays;
import java.util.List;

/**
 * 一件活"在做什么"给主人看的那一句:语言键 + 现场的词。头顶气泡、档案页读的是它,按显示时的语言现翻;
 * 给模型看的那句另走 {@link TaskRecord#describe()},两句各自从任务的状态生成,互不拼接。
 *
 * <p>键由任务所属的模组自己的语言文件给({@code numen.activity.*}、插件的键);{@code args} 是现场的词(物品 id、数量、坐标),
 * 按键对应的译文里 {@code %s} 的顺序。
 *
 * @param key  语言键
 * @param args 现场的词
 */
public record Activity(String key, List<String> args) {

    /** 没有自己的说法的任务:只说工具名({@code args[0]})。 */
    public static final String DEFAULT_KEY = "numen.activity.default";

    public Activity {
        args = List.copyOf(args);
    }

    public static Activity of(String key, Object... args) {
        return new Activity(key, Arrays.stream(args).map(String::valueOf).toList());
    }

    /** 当前语言下的那句话。 */
    public Component text() {
        return Component.translatable(key, args.toArray());
    }
}
