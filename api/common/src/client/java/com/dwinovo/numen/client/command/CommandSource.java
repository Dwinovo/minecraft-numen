package com.dwinovo.numen.client.command;

import com.dwinovo.numen.client.agent.EntityAgentLoop;

import java.util.List;

/**
 * 一批命令的出处。
 *
 * <h2>为什么顶层不直接持有命令表</h2>
 * 命令不只有一种来路。内置的那几条是写死的;技能那批得<b>按当前技能库现算</b>——
 * 主人往 {@code config/numen/skills} 里丢一个目录就该多出一条命令,不该要求他重启
 * 或者去哪儿手工登记一遍;将来第三方内容包还会有自己的一批。
 *
 * <p>所以 {@link ChatCommands} 只持有来源,每次补全与分发都向来源现问一遍。加一种
 * 新来路 = 多一个实现,顶层一行不用改。
 */
public interface CommandSource {

    /** 此刻这个来源能提供的命令。允许每次调用返回不同的内容。 */
    List<ChatCommand> commands(EntityAgentLoop loop);
}
