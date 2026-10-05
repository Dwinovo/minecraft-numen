package com.dwinovo.numen.core.tools.time;

import com.dwinovo.numen.api.NumenApi;
import com.dwinovo.numen.core.task.wait.WaitTaskRecord;
import com.dwinovo.numen.sdk.Doc;
import com.dwinovo.numen.sdk.Example;
import com.dwinovo.numen.sdk.Fn;
import com.dwinovo.numen.sdk.Job;
import com.dwinovo.numen.sdk.Note;
import com.dwinovo.numen.sdk.SeeAlso;
import com.dwinovo.numen.sdk.ServerCall;

/**
 * {@code numen.time}:让程序停一会儿。{@code wait} 是站着等的那一段身体活,和别的活一样占着身体、主人一喊停就停;等到某件事成立
 * 为止是 Lua 模块 {@code numen.time.wait_until},一下一下地等着看。
 */
public final class TimeApi {

    private TimeApi() {}

    public static void install(NumenApi numen) {
        numen.api("time", "Pausing your program: waiting a while where you stand.", TimeApi.class);
    }

    /** 等多久。 */
    public record Wait(@Doc("How long to wait, in seconds (0.05-600).") double seconds) {}

    @Fn("Stand where you are for a number of seconds, then go on.")
    @Example("numen.time.wait(5)")
    // 一次至多等十分钟:再久的是 numen.task.timer 的事,身体不必站着
    @Note("It holds your body like any job: your owner's stop ends it early, and a stopped wait raises interrupted. "
            + "Up to 600 seconds; for longer, numen.task.timer reminds you without standing still.")
    @Note("Returns how many seconds you actually waited.")
    @SeeAlso("numen.task.timer")
    public static Job<Double> wait(ServerCall call, Wait args) {
        return Job.of(new WaitTaskRecord(call, args.seconds()));
    }
}
