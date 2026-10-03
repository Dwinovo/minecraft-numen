package com.dwinovo.numen.core.tools.time;

import com.dwinovo.numen.agent.script.ScriptType;
import com.dwinovo.numen.api.NumenApi;
import com.dwinovo.numen.cli.ArgType;
import com.dwinovo.numen.cli.CommandArgs;
import com.dwinovo.numen.cli.CommandGroup;
import com.dwinovo.numen.cli.Param;
import com.dwinovo.numen.cli.ServerSource;
import com.dwinovo.numen.core.task.wait.WaitTaskRecord;
import com.dwinovo.numen.task.TaskDispatch;

/**
 * {@code time}:让程序停一会儿。{@code wait} 是站着等的那一段身体活,和别的活一样占着身体、主人一喊停就停;等到某件事成立
 * 为止是 Lua 模块 {@code numen.time.wait_until},一下一下地等着看。
 */
public final class TimeCommands {

    static final String GROUP = "time";

    /** 一次至多等十分钟:再久的是 {@code numen.task.timer} 的事,身体不必站着。 */
    private static final double MAX_WAIT_S = 600;

    private static final Param<Double> SECONDS = Param.required("seconds", ArgType.number(0.05, MAX_WAIT_S),
            "How long to wait, in seconds.");

    private TimeCommands() {}

    public static void install(NumenApi numen) {
        numen.registerCommands(GROUP, "Pausing your program: waiting a while where you stand.",
                TimeCommands::actions);
    }

    private static void actions(CommandGroup time) {
        time.server("wait", "Stand where you are for a number of seconds, then go on.",
                        TimeCommands::waitFor, SECONDS)
                .returns("waited", ScriptType.NUMBER)
                .example("numen.time.wait(5)")
                .note("It holds your body like any job: your owner's stop ends it early, and a stopped wait raises "
                        + "interrupted. Up to " + (int) MAX_WAIT_S + " seconds; for longer, numen.task.timer reminds "
                        + "you without standing still.")
                .note("Returns how many seconds you actually waited.")
                .seeAlso("task timer");
    }

    private static void waitFor(ServerSource src, CommandArgs args) {
        TaskDispatch.setTask(src, new WaitTaskRecord(src, args.get(SECONDS)));
    }
}
