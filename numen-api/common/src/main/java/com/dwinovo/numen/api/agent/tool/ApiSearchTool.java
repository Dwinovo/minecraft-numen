package com.dwinovo.numen.api.agent.tool;

import com.dwinovo.numen.api.agent.llm.ToolOutcome;
import com.dwinovo.numen.api.sdk.ApiSearch;
import com.dwinovo.numen.script.ScriptEngine;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.Map;

/**
 * 她查 Lua API 说明的工具,照 Claude Code 的 ToolSearch:上下文里只有一行插件名字空间,要用哪个、哪个函数怎么写,来这里读。
 * 文字由服务端按活的登记表算({@link ApiSearch}),大脑在主人的电脑上,经座位去要({@link ToolAnchor#searchApi});这里只收参数、交结果。
 * 属于引擎:不点名任何插件,各插件的用法由它们自己登记的说明与函数说明讲。
 */
public final class ApiSearchTool implements NumenTool {

    /** 工具名。 */
    public static final String NAME = ScriptEngine.DOCS_TOOL;

    private static final String QUERY = "query";
    private static final String MAX_RESULTS = "max_results";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "Fetches the documentation of the Lua API so you can call it from the " + ScriptEngine.IN_USE.toolName()
                + " tool. The plugins available right now are listed by name; until you fetch one, you know only its "
                + "name, not its functions or how to use them.\n\n"
                + "Query forms:\n"
                + "- \"select:minecraft,ysm.model.set\" — fetch these exact names. A plugin returns its guide (what it "
                + "is for, its groups and functions, how they fit together, examples); a group returns every function's "
                + "signature; a function returns its full signature, arguments, result fields, examples and notes.\n"
                + "- \"smelt furnace\" — keyword search across every plugin, up to max_results best matches.\n"
                + "- \"+ysm model\" — require \"ysm\" in the name, rank by the remaining terms.";
    }

    /** 只读登记表:查多少次世界与她都和之前一样。 */
    @Override
    public ToolHints hints() {
        return ToolHints.reads("Look up API");
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.object()
                .string(QUERY, "Query to find API documentation. Use \"select:<name>[,<name>...]\" to fetch exact "
                        + "names, or keywords to search.")
                .optionalInteger(MAX_RESULTS, "Maximum number of results to return (default: " + ApiSearch.DEFAULT_RESULTS
                        + ").", 1, ApiSearch.MAX_RESULTS)
                .build();
    }

    /** 面板上读作:查的是什么。 */
    @Override
    public String summary(JsonObject args) {
        JsonElement query = args.get(QUERY);
        return query != null && query.isJsonPrimitive() ? query.getAsString().replaceAll("\\s+", " ").strip() : "";
    }

    /** 文字在服务端,要等座位上的答复,所以结果在答复到了的那一刻才交。 */
    @Override
    public void invoke(ToolCall call) {
        JsonObject args;
        try {
            args = call.args();
        } catch (IllegalArgumentException bad) {
            call.complete(ToolOutcome.failure(bad.getMessage()));
            return;
        }
        JsonElement query = args.get(QUERY);
        if (query == null || !query.isJsonPrimitive() || query.getAsString().isBlank()) {
            call.complete(ToolOutcome.failure("argument '" + QUERY + "' is missing: the names to select or the keywords"));
            return;
        }
        JsonElement max = args.get(MAX_RESULTS);
        int maxResults = max == null || max.isJsonNull() ? 0 : max.getAsInt();
        call.ctx().searchApi(query.getAsString(), maxResults).whenComplete((text, failed) -> {
            if (failed == null) {
                call.complete(ToolOutcome.success(text));
            } else {
                Throwable cause = failed instanceof java.util.concurrent.CompletionException && failed.getCause() != null
                        ? failed.getCause() : failed;
                call.complete(ToolOutcome.failure(cause.getMessage()));
            }
        });
    }
}
