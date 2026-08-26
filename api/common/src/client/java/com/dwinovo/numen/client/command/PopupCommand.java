package com.dwinovo.numen.client.command;

import com.dwinovo.numen.client.agent.EntityAgentLoop;
import com.dwinovo.numen.client.ui.widget.Popup;

/**
 * 打开一层界面、而不是跑一下就完的命令。
 *
 * <h2>为什么另开一个接口</h2>
 * {@link ChatCommand#run} 只回"给主人看什么"——一段文字。有些命令要的不是一段文字,
 * 是一层贴着输入框弹出来的界面。与其往 {@code ChatCommand} 上加一个多数命令用不到的
 * 方法,不如让需要的那几条<b>多实现一个接口</b>:分发时问一句"你是不是这种",是就把
 * 那一层交给输入框。加能力靠组合,不靠往接口上堆字段。
 *
 * <h2>界面长什么样由命令自己决定</h2>
 * 返回的是 {@link Popup},不是某个具体控件:{@code /skills} 给一份能上下选的名单
 * ({@code SelectPanel}),{@code /usage} 给一张有构成条的读数卡({@code Readout})。
 * 弹层怎么摆、什么时候收,归输入框管;里面画什么,归命令管。
 */
public interface PopupCommand extends ChatCommand {

    /** 这条命令要弹出来的那一层。 */
    Popup popup(EntityAgentLoop loop);

    /**
     * 没有界面的调用方走到这儿——弹层是界面的东西,给不了。
     *
     * <p>不静默:说清楚它得在聊天面板里用,比什么都不发生好查。
     */
    @Override
    default String run(EntityAgentLoop loop, String args) {
        return ChatCommands.PREFIX + name() + " 要在聊天面板里用。";
    }
}
