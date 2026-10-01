package com.dwinovo.numen.cli;

/**
 * 一行命令写不通时的说法,两层(Numen 的命令与原版、模组的指令)同一种:依次 {@code error:} 错在哪、{@code usage:} 正确的写法、
 * {@code hint:} 能照抄的下一步。和常见命令行工具出错时打的那几行一个样子,她一眼认得出该改哪儿。
 */
final class Problem {

    private Problem() {}

    /**
     * @param error 错在哪(Brigadier 的原话与出错位置,或一句说明)
     * @param usage 正确的写法,可以多行;没有可给的为 null
     * @param hint  下一步
     */
    static String of(String error, String usage, String hint) {
        StringBuilder sb = new StringBuilder("error: ").append(error);
        if (usage != null) {
            sb.append("\nusage: ").append(usage);
        }
        return sb.append("\nhint: ").append(hint).toString();
    }
}
