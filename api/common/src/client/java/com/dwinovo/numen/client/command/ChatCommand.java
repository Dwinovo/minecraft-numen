package com.dwinovo.numen.client.command;

import com.dwinovo.numen.client.agent.EntityAgentLoop;

import java.util.List;

/**
 * 聊天框里的一条斜杠命令。
 *
 * <h2>命令是主人对客户端说的话</h2>
 * 不是对模型说的。{@code /build} 不是"建议她用建房子技能",是主人已经定了——所以它
 * 在本地跑完,要不要让模型知道、让它知道什么,由命令自己决定,不经过模型的判断。
 *
 * <h2>为什么 {@link #run} 只回一个字符串</h2>
 * 命令想干的一切(展开技能、开一轮、压缩、翻开关)本来就是 {@link EntityAgentLoop}
 * 上的方法,它自己去调。框架真正需要拿回来的只有一样:<b>给主人看什么</b>。
 *
 * <p>如果这里返回的是"结果的种类",那么每多一种命令就得往那个种类上加一格,而
 * "命令能干什么"这份清单是列不完的——那等于把它写死进框架。
 *
 * <h2>注册</h2>
 * 内置命令在 {@link ChatCommands} 的静态块里自注册;成批的(比如每个技能一条)走
 * {@link CommandSource}。
 */
public interface ChatCommand {

    /** 命令名,不带斜杠。补全按它前缀过滤,分发按它查找(忽略大小写)。 */
    String name();

    /** 一句话说明,显示在补全行右侧。 */
    String description();

    /** 参数提示(如 {@code [要求]});{@code null} = 这条命令不吃参数。 */
    default String argHint() {
        return null;
    }

    /**
     * 这条命令会不会改变<b>她</b>看到的东西。
     *
     * <p>纯说明:补全行上一个记号,让主人一眼分得清"这条只是给我看"({@code /skills})
     * 和"这条会动她"({@code /build})。不参与分发——写错了顶多提示不准,不会让命令
     * 的行为出错。
     */
    default boolean touchesContext() {
        return false;
    }

    /**
     * 此刻不能用的理由;{@code null} = 能用。
     *
     * <p>补全行据此灰掉并把理由显示出来。这是斜杠命令比按钮强的地方:按钮灰着不说话,
     * 这里灰着还告诉你为什么。
     */
    default String unavailable(EntityAgentLoop loop) {
        return null;
    }

    /** 参数的补全候选;{@code partial} 是已经打出来的那截参数(可能是空串)。 */
    default List<Completion> completeArgs(EntityAgentLoop loop, String partial) {
        return List.of();
    }

    /**
     * 干活。
     *
     * @param args 命令名之后的原文,已 trim;没有参数时是空串,不会是 {@code null}
     * @return 给主人看的话(可多行);{@code null} = 不吭声
     */
    String run(EntityAgentLoop loop, String args);
}
