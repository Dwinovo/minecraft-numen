package com.dwinovo.numen.bench;

import net.neoforged.fml.common.Mod;

/**
 * 评测这个模组:只在评测的运行配置里加载,平时的游戏、GameTest 与发行 jar 里都没有它。
 *
 * <p>评测的进程是服务端,技能表由服务器起来时的 {@code ServerBrains} 接,和产品同一处,这里不另接。
 */
@Mod(NumenBench.MOD_ID)
public final class NumenBench {

    /** 模组 id,也是评测用例的命名空间。 */
    public static final String MOD_ID = "numen_bench";

    public NumenBench() {
    }
}
