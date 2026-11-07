package com.dwinovo.numen.tools.shape;

import java.util.function.Supplier;
import net.minecraft.util.RandomSource;

/**
 * 造形(numen.shape)掷骰子用的随机数从哪来:生成器(地物、结构、树林、花草)在记录世界上的一次生成、按几率选格的筛子、高度图笔刷的进位。
 * 正式运行每次一个不定种子的;GameTest 换成给定种子的({@link #use}),同一次调用每次得到同一个结果,一次失败能原样重放。
 */
public final class ShapeRandom {

    private static volatile Supplier<RandomSource> source = RandomSource::create;

    private ShapeRandom() {}

    /** 从此每次调用用 {@code next} 给的随机数:GameTest 的接缝。 */
    public static void use(Supplier<RandomSource> next) {
        source = next;
    }

    /** 这一次调用的随机数。 */
    static RandomSource next() {
        return source.get();
    }
}
