package com.dwinovo.numen.api.sdk;

import com.dwinovo.numen.api.knowledge.KnowledgeKind;
import com.dwinovo.numen.api.knowledge.KnowledgeSources;

import java.util.List;

/**
 * {@code numen.api}:API 自己的事。{@code numen.api.sources} 说 {@code numen.inv.recipes} 等动词背后有哪些来源;API 的说明不在这里,
 * 经 {@code ApiSearch} 工具查({@link ApiSearch})。
 */
public final class HelpApi {

    private HelpApi() {}

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
