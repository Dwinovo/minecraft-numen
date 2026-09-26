package com.dwinovo.numen.cli;

/**
 * 模型写的一行落在哪一层。路由只有这一条规则,两侧都经这里分:
 *
 * <ul>
 *   <li>行首是 {@code /}:第 0 层,MC 的指令树(原版与模组的指令)。原样交给服务端,以她自己的权限执行,和玩家在聊天栏里
 *       敲的一样({@link CommandRunner})。</li>
 *   <li>否则:第 1 层,Numen 给她的命令层({@link NumenCli})。</li>
 * </ul>
 *
 * <p>第 1 层认不出的一行不转给第 0 层:那样同一个词两层都能答,写错了也说不清错在哪一层。
 *
 * @param mc   是不是第 0 层
 * @param text 去掉首尾空白与第 0 层标记之后的那一行
 */
record Line(boolean mc, String text) {

    /** 第 0 层的标记。 */
    static final String MC = "/";

    static Line of(String typed) {
        String line = typed.strip();
        return line.startsWith(MC) ? new Line(true, line.substring(MC.length()).strip()) : new Line(false, line);
    }
}
