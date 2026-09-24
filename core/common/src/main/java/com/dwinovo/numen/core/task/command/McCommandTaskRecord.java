package com.dwinovo.numen.core.task.command;

import com.dwinovo.numen.cli.ServerSource;
import com.dwinovo.numen.task.TaskRecord;

/**
 * {@code numen mc} 的一次执行:以她的身份跑 {@link #line} 这一条游戏指令。
 */
public final class McCommandTaskRecord extends TaskRecord {

    /** 整行,不带前导 {@code /}。 */
    public final String line;

    /** 名字与调用 id 取自这次调用的源:从 {@code numen} 进来就叫 {@code mc}。 */
    public McCommandTaskRecord(ServerSource source, long deadlineGameTime, String line) {
        super(source, deadlineGameTime);
        this.line = line;
    }

    @Override
    public String describe() {
        return "numen mc /" + line;
    }
}
