package com.dwinovo.numen.client.command;

import com.dwinovo.numen.client.agent.EntityAgentLoop;
import com.dwinovo.numen.client.ui.widget.SelectPanel;

/**
 * 打开一个面板、而不是跑一下就完的命令。
 *
 * <h2>为什么另开一个接口</h2>
 * {@link ChatCommand#run} 只回"给主人看什么"——一段文字。有些命令要的不是一段文字,
 * 是一个能上下选、能按回车改东西的界面。与其往 {@code ChatCommand} 上加一个多数命令
 * 用不到的方法,不如让需要的那几条<b>多实现一个接口</b>:分发时问一句"你是不是这种",
 * 是就交给界面。加能力靠组合,不靠往接口上堆字段。
 */
public interface PageCommand extends ChatCommand {

    /** 这条命令要打开的面板内容。 */
    SelectPanel.Page page(EntityAgentLoop loop);

    /**
     * 没有界面的调用方走到这儿——面板是界面的东西,给不了。
     *
     * <p>不静默:说清楚它得在聊天面板里用,比什么都不发生好查。
     */
    @Override
    default String run(EntityAgentLoop loop, String args) {
        return ChatCommands.PREFIX + name() + " 要在聊天面板里用。";
    }
}
