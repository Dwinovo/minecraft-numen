package com.dwinovo.numen.api.sdk;

import com.dwinovo.numen.script.ApiError;
import com.dwinovo.numen.script.ErrorKind;
import com.dwinovo.numen.api.knowledge.KnowledgeKind;
import com.dwinovo.numen.api.knowledge.KnowledgeSources;
import com.dwinovo.numen.api.script.Modules;

import java.util.List;

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
    public static String help(ServerCall call, Help args) {
        String text = ApiDocs.help(args.name(), Modules.of(call.companion()));
        if (text == null) {
            throw new ApiError(ErrorKind.NOT_FOUND, "there is no function, group or module named " + args.name(),
                    "the <api> index lists every group; " + Call.of("numen.api.help", "numen.move") + " lists one.");
        }
        return text;
    }

    /** 要哪个动词的来源。 */
    public record Sources(@Doc("The verb: recipes, uses or info.") KnowledgeKind kind) {}

    /** 一个来源。 */
    @Doc("One source behind numen.inv.recipes, numen.inv.uses or numen.inv.info.")
    public record SourceInfo(@Doc("Its name: the plugin that registered it; numen is the game's own recipe list.") String id,
                             @Doc("The function that asks it.") String call,
                             @Doc("Where it answers: server, or client for your owner's client.") ApiFunction.Side side,
                             @Doc("The kind of information it adds.") String provides,
                             @Doc("What it cannot say, so a result has no value for these: never guessed.")
                             List<String> notProvided) {}

    @Fn("The sources behind numen.inv.recipes, numen.inv.uses or numen.inv.info: who answers, where, what each adds and "
            + "cannot say.")
    @Example("for _, s in ipairs(numen.api.sources(\"recipes\")) do print(s.id, s.side, s.provides) end")
    @Note("Instant and read-only; it lists what is registered now. Whether a source on your owner's client answers is "
            + "only known when it is asked: the verb's result says which source did not and why.")
    public static List<SourceInfo> sources(ServerCall call, Sources args) {
        return KnowledgeSources.of(args.kind()).stream().map(s -> new SourceInfo(s.id(), s.call(), s.side(), s.provides(),
                s.notProvided())).toList();
    }
}
