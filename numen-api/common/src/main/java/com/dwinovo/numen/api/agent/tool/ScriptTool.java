package com.dwinovo.numen.api.agent.tool;

import com.dwinovo.numen.script.ScriptEngine;
import com.dwinovo.numen.script.ScriptLimits;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.Map;

/**
 * 她在世界里做事的工具:一段程序(语言见 {@link ScriptEngine},眼下是 Lua),一次调用跑完,回一张回执。程序里的每个 API 函数
 * ({@code plugin.work.dig(b)})是登记处的一个函数,读法与执行见 {@code com.dwinovo.numen.api.sdk.Dispatcher};模块里的函数
 * ({@code plugin.move.walk})用同一种语言写成,程序按名字直接用。工具名与程序怎么写随脚本语言,其余都与语言无关。
 *
 * <p>程序由大脑的派发器跑({@code SerialCalls} 认出这个工具,经 {@code ScriptCall} 逐个派调用、等身体收尾、在调用之间停下),
 * 不经 {@link #invoke}。外接大脑经 {@code LoopHands} 跑它,交给的是同伴自己的那一个派发器(内置循环闲着时借用)。
 */
public final class ScriptTool implements NumenTool {

    /** 参数里程序的那个键。 */
    private static final String CODE = "code";
    /** 参数里这段程序做什么的那一句:主人在面板上读到的。 */
    private static final String DESCRIPTION = "description";

    /**
     * 一次调用写下的东西。
     *
     * @param code        程序
     * @param description 这段程序做什么,一句话,用她和主人说话的语言写
     */
    public record Program(String code, String description) {}

    @Override
    public String name() {
        return ScriptEngine.IN_USE.toolName();
    }

    @Override
    public String description() {
        // 照 Claude Code 的工具描述写:动词起头,只说它做什么、环境是什么样;对所有插件一样,不点名任何一个的函数
        ScriptEngine engine = ScriptEngine.IN_USE;
        return "Runs a " + engine.language() + " program that acts through your API, and returns one receipt when it "
                + "ends. This is how you do anything in the world.\n"
                + "- " + engine.howToCall() + "\n"
                + "- You know a plugin only by its name, which the system prompt lists. Before you use one, read it "
                + "with " + ScriptEngine.DOCS_TOOL + ": its guide, then the signature of the function you are about to "
                + "call. Don't guess a name or an argument.\n"
                + "- One call is a one-line program. When each next step follows from what a call returned — going "
                + "through what a scan found, repeating until nothing is left, stopping on the first failure — write "
                + "the steps as one program.\n"
                + "- The receipt reads like a process's output. First line: how the program ended (`ok · 3 calls · 2 s`; "
                + "on an error: the line and the call's error, usage and hint). Then stderr: what your body did and what "
                + "went wrong, an entry for each call that had something to say, with its line and function (`line 5 "
                + "plugin.group.verb: killed a cow …`; a body job's entry is its whole account of what it changed; a "
                + "call that failed is there even when you caught it with pcall; a call that only returns data writes "
                + "nothing). Then what the program returned, and stdout: what it printed. A long list or deep table is "
                + "shortened to its first and last items with its size, never silently.\n"
                + "- A function that starts work for your body (walking, digging, fighting, building…) first checks "
                + "the world and plans; one that can't start (no path, nothing to dig, no tool) fails right there with "
                + "the reason, and whatever the body was doing goes on. The program waits for each job it starts to "
                + "end before its next line runs; a job that failed or timed out names the kind first (a timeout "
                + "reports progress, and the same call again resumes). A job with no end of its own (following, "
                + "guarding) is passed over: the program goes on past it.\n"
                + "- " + BODY_JOBS + "\n"
                + "- A run stops at " + ScriptLimits.COMMANDS + " API calls or " + ScriptLimits.WALL_MILLIS / 60_000
                + " minutes, and when it runs " + ScriptLimits.INSTRUCTIONS_PER_SLICE + " instructions without "
                + "calling one. Your owner speaking, an urgent event or the stop button stops it between calls and the "
                + "receipt says where; a body job it was waiting for then keeps running (the stop button stops it "
                + "too), and its end arrives later as an <event kind=\"task_finished\">.";
    }

    /** 身体同时只做一件后台活:任务槽({@code TaskDispatch})做的事,这条规则给模型读的说法只在这里。 */
    private static final String BODY_JOBS = "One body, one background job: starting another while one runs replaces it, "
            + "and the new receipt names the job it stopped. Quick actions step in on top of it and hand the body back.";

    /** 一段程序能做 API 里的任何事(拆、杀、放、丢),什么都可能发生:按最保守的写。 */
    @Override
    public ToolHints hints() {
        return new ToolHints("Run " + ScriptEngine.IN_USE.language() + " program", false, true, false, true);
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.object()
                .string(CODE, "The program.")
                .string(DESCRIPTION, "A clear, concise line, in active voice, saying what this program does, a few "
                        + "words: \"Mine the iron vein to the east\". Your owner reads it on the panel; write it in "
                        + "the language you speak with them (see # Speaking in the system prompt).")
                .build();
    }

    /** 面板上这次调用读作什么:它自己写的那一句。 */
    @Override
    public String summary(JsonObject args) {
        JsonElement line = args.get(DESCRIPTION);
        return line != null && line.isJsonPrimitive() ? line.getAsString().replaceAll("\\s+", " ").strip() : "";
    }

    /** 程序只由派发器跑(见类注释),调到这里是接线错了。 */
    @Override
    public void invoke(ToolCall call) {
        throw new IllegalStateException(name() + " runs through a dispatcher (SerialCalls), not through invoke");
    }

    /**
     * 这次调用里写的程序与它做什么的那一句。
     *
     * @param arguments 模型写的参数 JSON
     * @throws IllegalArgumentException 参数不是 JSON、程序或那一句没写(或空白、不是字符串)、或多写了别的
     */
    public static Program parse(String arguments) {
        JsonObject args;
        try {
            args = JsonParser.parseString(arguments).getAsJsonObject();
        } catch (RuntimeException notJson) {
            throw new IllegalArgumentException("invalid arguments JSON: " + notJson.getMessage());
        }
        for (String key : args.keySet()) {
            if (!key.equals(CODE) && !key.equals(DESCRIPTION)) {
                throw new IllegalArgumentException("unknown argument '" + key + "'; this takes: " + CODE + ", "
                        + DESCRIPTION);
            }
        }
        return new Program(text(args, CODE, "the program, as a string"),
                text(args, DESCRIPTION, "what the program does, in a few words"));
    }

    private static String text(JsonObject args, String key, String what) {
        JsonElement value = args.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()
                || value.getAsString().isBlank()) {
            throw new IllegalArgumentException("argument '" + key + "' is missing: " + what);
        }
        return value.getAsString();
    }

    /** 一段程序写成这个工具的一次调用的参数。 */
    public static JsonObject args(String code, String description) {
        JsonObject args = new JsonObject();
        args.addProperty(CODE, code);
        args.addProperty(DESCRIPTION, description);
        return args;
    }
}
