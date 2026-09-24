package com.dwinovo.numen.core.task.command;

import com.dwinovo.numen.task.TaskRecord;

/**
 * {@code numen mc} 的一次执行:以她的身份跑 {@link #line} 这一条游戏指令。
 */
public final class McCommandTaskRecord extends TaskRecord {

    /** 整行,不带前导 {@code /}。 */
    public final String line;

    /**
     * @param toolName 调用进来时用的工具名({@code numen})
     */
    public McCommandTaskRecord(String toolName, String toolCallId, long deadlineGameTime, String line) {
        super(toolName, toolCallId, deadlineGameTime);
        this.line = line;
    }

    @Override
    public String describe() {
        return "numen mc /" + line;
    }
}
