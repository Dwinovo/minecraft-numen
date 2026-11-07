package com.dwinovo.numen.plugins.kubejs;

import com.dwinovo.numen.script.ApiError;
import com.dwinovo.numen.script.ErrorKind;
import dev.latvian.mods.kubejs.script.ScriptType;

/**
 * 脚本里抛出的错怎么变回 API 的失败。Rhino 把 Java 方法抛的异常(函数说的 {@link ApiError})包成它自己的异常再经脚本帧往外抛,
 * 原样放过去的话调用方认不出那是函数说的失败;所以顺着 cause 找回 {@link ApiError}。找不到的是脚本自己的错(写错了、
 * 抛了字符串):写进 KubeJS 控制台,这次调用以 {@code failed} 收场。
 */
final class ScriptErrors {

    private ScriptErrors() {}

    /** 脚本自己的错写进 KubeJS 的服务端控制台,脚本作者在那里看。 */
    static void log(String what, RuntimeException error) {
        ScriptType.SERVER.console.error(what + " failed: " + error.getMessage(), error);
    }

    /** {@code error} 里的 API 失败;没有就是脚本自己的错,记控制台并说成这次调用的失败。 */
    static ApiError asFailure(String function, RuntimeException error) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof ApiError failed) {
                return failed;
            }
        }
        log("Numen function " + function, error);
        return new ApiError(ErrorKind.FAILED, function + " failed in its script: " + error.getMessage(), null);
    }
}
