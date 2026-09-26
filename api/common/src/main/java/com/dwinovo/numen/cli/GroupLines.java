package com.dwinovo.numen.cli;

import com.mojang.brigadier.exceptions.CommandSyntaxException;

/**
 * 在一棵树上读本组的一行命令(不带组名):{@link ArgType#command} 这种参数的值从这里来。由正在读外面那一行的
 * {@link CommandTree} 给出,所以里面那一条和外面那一行是同一棵树读的。
 */
@FunctionalInterface
interface GroupLines {

    /**
     * @param afterGroup 组名之后的那一截,如 {@code layer 0 1 0 ###}
     * @throws CommandSyntaxException 读不通;消息和单独执行这一条时写错一样
     */
    NumenCli.Reading read(String afterGroup) throws CommandSyntaxException;
}
