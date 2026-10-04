package com.dwinovo.numen.sdk;

import com.dwinovo.numen.agent.loop.SerialCalls;
import com.dwinovo.numen.agent.loop.ToolPort;
import com.dwinovo.numen.agent.provider.LlmToolCall;
import com.dwinovo.numen.agent.script.ApiError;
import com.dwinovo.numen.agent.script.ScriptCatalog;
import com.dwinovo.numen.agent.script.ScriptEngine;
import com.dwinovo.numen.agent.script.ScriptRun;
import com.dwinovo.numen.agent.tool.ScriptTool;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.script.BuiltinModules;
import com.dwinovo.numen.script.Modules;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 给写 API 的人用的:在同一个进程里跑一段程序看回执({@link #run}),和一份 lint 报告({@link #lint})——写法上的问题都在这里报,
 * 不在登记时拦:登记只拦会破坏系统的(见 {@link Binder})。好写法省不省事、模型用得顺不顺,由评测的分数说话。
 *
 * <h2>lint 看什么</h2>
 * <ul>
 *   <li>函数:没写一句话说明;参数没写 {@link Doc};没写例子;例子读不通、没调到它自己、参数读不成;相关函数({@link SeeAlso})不存在;</li>
 *   <li>随模组发的模块:装出来不是一张表、给登记的函数赋值(运行时同一条规则照样拦着);开头没写一行说明;函数上面没写注释
 *       (帮助与索引里它的说明就是那几行);</li>
 *   <li>文字里写着的调用({@link #lint(List)}:技能、提示词):写在反引号或 {@code ```lua} 代码块里、以一组的函数打头的那些,
 *       读不通、参数读不成、点名的函数不存在。</li>
 * </ul>
 */
public final class ApiTester {

    private ApiTester() {}

    /** 一条 lint:在哪、什么问题。 */
    public record Lint(String where, String problem) {

        @Override
        public String toString() {
            return where + ": " + problem;
        }
    }

    /** 一段待查的文字:它在哪(给报告指路)与正文。 */
    public record Text(String where, String body) {}

    // ---- lint ----

    /** 登记了的函数与随模组发的模块的写法问题,按函数全名、模块名的顺序。 */
    public static List<Lint> lint() {
        List<Lint> out = new ArrayList<>();
        Modules factory = Modules.factory();
        ScriptCatalog catalog = ApiRegistry.catalog(factory);
        for (ApiFunction fn : ApiRegistry.functions()) {
            String where = fn.fullName();
            if (fn.summary().isBlank()) {
                out.add(new Lint(where, "no one-line summary (@Fn(\"…\"))"));
            }
            for (ApiFunction.Param p : fn.params()) {
                if (p.doc() == null) {
                    out.add(new Lint(where, "argument " + p.name() + " has no @Doc"));
                }
            }
            if (fn.examples().isEmpty()) {
                out.add(new Lint(where, "no @Example: the model writes after examples more reliably than after "
                        + "a grammar"));
            }
            for (String example : fn.examples()) {
                String problem = exampleProblem(fn, example, catalog);
                if (problem != null) {
                    out.add(new Lint(where, "the example `" + example + "` " + problem));
                }
            }
            for (String related : fn.seeAlso()) {
                if (ApiRegistry.function(related) == null && !ApiDocs.library(factory).containsKey(related)) {
                    out.add(new Lint(where, "@SeeAlso names " + related + ", which is no function"));
                }
            }
        }
        ScriptEngine engine = ScriptEngine.IN_USE;
        BuiltinModules.all().forEach((name, module) -> {
            String broken = engine.checkModule(name, module.code(), catalog);
            if (broken != null) {
                out.add(new Lint("module " + name, broken));
            }
            if (module.summary() == null) {
                out.add(new Lint("module " + name, "no first comment line saying what it does ("
                        + engine.comment("...") + ")"));
            }
            for (ScriptEngine.Defined fn : engine.functions(name, module.code())) {
                if (engine.summaryOf(fn).isEmpty()) {
                    out.add(new Lint("module " + name, "the function " + fn.name() + " has no comment above it"));
                }
            }
        });
        return out;
    }

    /** 一个例子的问题:读不通、没调到它自己、哪一次调用的参数读不成;没问题是 null。 */
    private static String exampleProblem(ApiFunction fn, String example, ScriptCatalog catalog) {
        ScriptEngine.Reading reading;
        try {
            reading = ScriptEngine.IN_USE.calls(fn.fullName(), example, catalog);
        } catch (IllegalArgumentException unreadable) {
            return "does not read: " + unreadable.getMessage();
        }
        if (reading.error() != null) {
            return "stops: " + reading.error();
        }
        boolean here = false;
        for (ScriptRun.Call call : reading.calls()) {
            String problem = callProblem(call);
            if (problem != null) {
                return problem;
            }
            here |= call.function().equals(fn.fullName());
        }
        return here ? null : "does not call " + fn.fullName();
    }

    /** 一次调用读不读得成:是登记的函数就按它的参数表读;模块函数不读参数。读得成是 null。 */
    private static String callProblem(ScriptRun.Call call) {
        if (ApiRegistry.function(call.group() + "." + call.name()) == null) {
            return null;
        }
        try {
            Dispatcher.invocation(call);
            return null;
        } catch (ApiError wrong) {
            return "calls " + call.function() + " wrongly: " + wrong.getMessage().split("\n")[0];
        }
    }

    /** 反引号里的一段。 */
    private static final Pattern SPAN = Pattern.compile("`([^`\n]+)`");
    /** 以一个组的函数(或模块函数)打头:{@code numen.work.dig(...)}、{@code local r = numen.scan.blocks(...)}、{@code numen.move.to}。 */
    private static final Pattern CALL = Pattern.compile("^(?:local\\s+[A-Za-z_][A-Za-z0-9_]*\\s*=\\s*)?"
            + "([a-z][a-z0-9_]*)\\.([a-z][a-z0-9_]*)\\.([A-Za-z_][A-Za-z0-9_]*)");
    /** 只点名一个函数或一组:{@code numen.inv.recipes}、{@code numen.inv}。 */
    private static final Pattern MENTION = Pattern.compile(
            "^([a-z][a-z0-9_]*)\\.([a-z][a-z0-9_]*)(?:\\.([A-Za-z_][A-Za-z0-9_]*))?$");

    /**
     * 文字里写着的调用的问题:写在反引号里、或 {@code ```lua} 代码块里(整块是一段程序)、以一组的函数打头的那些。只点名一个函数或一组的
     * 要存在;一段调用要读得通、每次调用的参数要读得成。
     */
    public static List<Lint> lint(List<Text> texts) {
        List<Lint> out = new ArrayList<>();
        Modules factory = Modules.factory();
        ScriptCatalog catalog = ApiRegistry.catalog(factory);
        for (Text text : texts) {
            for (String code : written(text.body())) {
                String problem = writtenProblem(code, catalog, factory);
                if (problem != null) {
                    out.add(new Lint(text.where(), "`" + code.split("\n")[0] + "` " + problem));
                }
            }
        }
        return out;
    }

    /** 这段文字里写着的调用,按出现的顺序:先是代码块里的,再是反引号里的。 */
    static List<String> written(String text) {
        List<String> found = new ArrayList<>();
        boolean fenced = false;
        boolean lua = false;
        StringBuilder block = new StringBuilder();
        StringBuilder prose = new StringBuilder();
        for (String raw : text.split("\n", -1)) {
            String line = raw.strip();
            if (line.startsWith("```")) {
                if (fenced && lua && !block.isEmpty()) {
                    found.add(block.toString().stripTrailing());
                }
                lua = !fenced && line.equals("```lua");
                fenced = !fenced;
                block.setLength(0);
                continue;
            }
            if (fenced && lua) {
                block.append(raw).append('\n');
            } else if (!fenced) {
                prose.append(raw).append('\n');
            }
        }
        Matcher m = SPAN.matcher(prose);
        while (m.find()) {
            String span = m.group(1).strip();
            Matcher call = CALL.matcher(span);
            if (call.find() || MENTION.matcher(span).matches() && span.contains(".")) {
                found.add(span);
            }
        }
        return found;
    }

    private static String writtenProblem(String code, ScriptCatalog catalog, Modules factory) {
        Matcher mention = MENTION.matcher(code);
        if (mention.matches()) {
            boolean group = mention.group(3) == null;
            boolean known = group ? ApiRegistry.group(code) != null || factory.get(code) != null
                    : ApiRegistry.function(code) != null || ApiDocs.library(factory).containsKey(code);
            boolean namespaced = ApiRegistry.groups().stream().anyMatch(g -> g.namespace().equals(mention.group(1)))
                    || factory.names().stream().anyMatch(n -> n.startsWith(mention.group(1) + "."));
            return known || !namespaced ? null : "names " + code + ", which does not exist";
        }
        ScriptEngine.Reading reading;
        try {
            reading = ScriptEngine.IN_USE.calls("written", code, catalog);
        } catch (IllegalArgumentException unreadable) {
            return "does not read: " + unreadable.getMessage();
        }
        if (reading.calls().isEmpty() && reading.error() != null) {
            return "stops: " + reading.error();
        }
        for (ScriptRun.Call call : reading.calls()) {
            String problem = callProblem(call);
            if (problem != null) {
                return problem;
            }
        }
        return null;
    }

    // ---- 进程内跑一段程序 ----

    /**
     * 跑完的一段程序:整张回执,与它派出的每次调用的结果,按先后。
     *
     * @param receipt 整张回执({@code success}、{@code message}、{@code data})
     * @param replies 每次调用的结果({@link com.dwinovo.numen.agent.script.ApiReply} 的那一份)
     */
    public record Run(JsonObject receipt, List<String> replies) {

        /** 跑到了最后。 */
        public boolean ok() {
            return receipt.get("success").getAsBoolean();
        }

        /** 回执那段话。 */
        public String message() {
            return receipt.get("message").getAsString();
        }

        /** 程序 {@code return} 的值(JSON);没有是 null。 */
        public com.google.gson.JsonElement returned() {
            JsonObject data = receipt.getAsJsonObject("data");
            return data == null ? null : data.get(com.dwinovo.numen.agent.script.ScriptCall.RETURNED);
        }

        /** 最后一次调用的结果;一次都没派出是 null。 */
        public JsonObject lastReply() {
            return replies.isEmpty() ? null : JsonParser.parseString(replies.getLast()).getAsJsonObject();
        }
    }

    /**
     * 在这个进程里跑一段程序,当场跑完的才有回执:服务端函数在这里执行({@code her} 是她的身体,没有世界的单测给 null),客户端函数也在
     * 这里答,模块只有出厂那一套。占身体的活要世界一刻一刻地走,不在这里等:那样的程序用 GameTest。
     *
     * @throws IllegalStateException 程序没在当场跑完(在等身体的活或主人的答复)
     */
    public static Run run(NumenPlayer her, UUID companion, String code) {
        List<String> replies = new ArrayList<>();
        ProgramPort port = new ProgramPort(her, companion, Modules.factory(), true, new ProgramPort.Observer() {
            @Override
            public void replied(LlmToolCall line, String reply) {
                replies.add(reply);
            }
        });
        String[] receipt = new String[1];
        new SerialCalls(port).run(List.of(new LlmToolCall("test-" + UUID.randomUUID(),
                ScriptEngine.IN_USE.toolName(), ScriptTool.args(code).toString())), new ToolPort.Sink() {
            @Override
            public void started(LlmToolCall call) {
            }

            @Override
            public void finished(LlmToolCall call, String result) {
                receipt[0] = result;
            }

            @Override
            public void settled() {
            }
        });
        if (receipt[0] == null) {
            throw new IllegalStateException("the program did not end at once: it waits for a body job or an answer; "
                    + "run it in a GameTest");
        }
        return new Run(JsonParser.parseString(receipt[0]).getAsJsonObject(), List.copyOf(replies));
    }
}
