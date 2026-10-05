package com.dwinovo.numen.sdk;

import com.dwinovo.numen.agent.script.ApiError;
import com.dwinovo.numen.agent.script.ErrorKind;
import com.dwinovo.numen.script.Modules;

/**
 * {@code numen.api}:API 自己的帮助。{@code numen.api.help("numen.work.dig")} 给一个函数的全部帮助,{@code numen.api.help("numen.work")}
 * 列一组;文字全由登记与模块里的注释生成({@link ApiDocs}),和系统提示里的索引、写错时附上的用法是同一份。
 */
public final class HelpApi {

    private HelpApi() {}

    /** 要谁的帮助。 */
    public record Help(@Doc("A function as the <api> index writes it (numen.work.dig, numen.move.to), a group "
            + "(numen.work), a namespace (numen) or a module.") String name) {}

    @Fn("The help of a function (its signature, every argument, what it returns, examples, notes) or of a group or "
            + "module (one typed line per function), as text.")
    @Example("print(numen.api.help(\"numen.work.dig\"))")
    @Example("print(numen.api.help(\"numen.move\"))")
    @Note("Instant; it reads the API's own declarations and changes nothing.")
    public static String help(ClientCall call, Help args) {
        String text = ApiDocs.help(args.name(), Modules.of(call.companion()));
        if (text == null) {
            throw new ApiError(ErrorKind.NOT_FOUND, "there is no function, group or module named " + args.name(),
                    "the <api> index lists every group; " + Call.of("numen.api.help", "numen.move") + " lists one.");
        }
        return text;
    }
}
