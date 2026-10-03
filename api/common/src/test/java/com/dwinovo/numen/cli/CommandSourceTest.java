package com.dwinovo.numen.cli;

import com.dwinovo.numen.task.TaskResult;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.dwinovo.numen.cli.CliFixture.door;
import static com.dwinovo.numen.cli.CliFixture.lua;
import static com.dwinovo.numen.cli.CliFixture.onServer;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 执行侧与同源:脚本里客户端动作当场跑、服务端动作在服务端跑;脚本与人写的一行命令按同一张参数表、同一种参数类型读,处理函数拿到
 * 相等的参数,派下的活叫脚本里的函数名。服务端那一侧收到的 JSON 再按同一种类型读一遍,读不成的回执是三段。回执与派下的活在真服务器
 * 上对得上号,在 GameTest 里验({@code TaskControlGameTests}、{@code CommandGameTests})。
 */
class CommandSourceTest {

    static final Param<String> REASON = Param.required("reason", ArgType.string(), "Why.");
    static final Param<Integer> AFTER = Param.optional("after_s", ArgType.integer(1, 1200), "Delay in seconds.")
            .whenOmitted("remind in a minute");
    static final Param<String> ID = Param.optional("id", ArgType.word(), "Which one.").whenOmitted("make a new one");
    static final Param<Integer> TRIES = Param.optional("tries", ArgType.integer(1, 5), "How often.")
            .whenOmitted("try once");

    /** 服务端处理函数收到的每一份参数。 */
    static final List<CommandArgs> SERVER_CALLS = new ArrayList<>();
    static final List<CommandArgs> CLIENT_CALLS = new ArrayList<>();

    @BeforeAll
    static void register() {
        door().registerCommands("gt_side", "A group with one action on each side.", g -> {
            g.server("remind", "Set a reminder.", (src, args) -> {
                SERVER_CALLS.add(args);
                src.reply(TaskResult.ok("reminder in " + args.get(AFTER) + "s: " + args.get(REASON),
                        Map.of("task", src.taskName())).toJson());
            }, REASON, AFTER).returns(com.dwinovo.numen.agent.script.ScriptType.NOTHING).example("gt.gt_side.remind(\"check the furnace\", {after_s = 60})");
            g.client("jot", "Jot something down on the owner's client.", (src, args) -> {
                CLIENT_CALLS.add(args);
                src.reply(TaskResult.ok("jotted " + args.get(ID) + " x" + args.get(TRIES)).toJson());
            }, ID, TRIES).returns(com.dwinovo.numen.agent.script.ScriptType.NOTHING).example("gt.gt_side.jot({id = \"a1\"})");
        });
    }

    @Test
    void aClientActionRunsOnTheClientAndAServerActionOnTheServer() {
        CLIENT_CALLS.clear();
        SERVER_CALLS.clear();
        CliFixture.Outcome run = lua("""
                return {gt.gt_side.jot({id = "a1"}), gt.gt_side.remind("check", {after_s = 5})}
                """);
        assertTrue(run.success(), run.message());
        assertEquals(1, CLIENT_CALLS.size());
        assertEquals(1, SERVER_CALLS.size());
    }

    /** 服务端的树上客户端动作只有名字与帮助,没有参数、执行不了:写到它那儿是一行没写完的命令,附上它的帮助。 */
    @Test
    void theServerTreeOnlyNamesAClientAction() {
        CliFixture.Outcome jot = onServer("gt gt_side jot --id a1");
        assertFalse(jot.success());
        assertTrue(jot.message().startsWith("error: Unknown command"), jot.message());
        assertTrue(jot.message().contains("\nusage: gt.gt_side.jot({id=…, tries=…})\n"), jot.message());
        assertTrue(onServer("gt gt_side jot --help").success(), "帮助两侧都答得出");
    }

    /**
     * 同一件事从两个前端进来,处理函数拿到的参数相等:脚本的调用读成 JSON、在服务端按同一张参数表读成值
     * ({@link CommandArgs#fromJson}),一行命令从服务端那棵树上读。派下的活叫脚本里的函数名。
     */
    @Test
    void aScriptAndALineHandTheSameArgumentsToTheSameHandler() {
        SERVER_CALLS.clear();
        CliFixture.Outcome viaScript = lua("return gt.gt_side.remind(\"check the furnace\", {after_s = 60})");
        CliFixture.Outcome viaLine = onServer("gt gt_side remind \"check the furnace\" --after-s 60");

        assertEquals(2, SERVER_CALLS.size());
        JsonObject json = new JsonObject();
        json.addProperty("after_s", 60);
        json.addProperty("reason", "check the furnace");
        assertEquals(CommandArgs.fromJson(List.of(REASON, AFTER), json), SERVER_CALLS.get(0));
        assertEquals(SERVER_CALLS.get(0), SERVER_CALLS.get(1), "两个前端读出的参数相等");
        assertEquals("reminder in 60s: check the furnace", viaLine.message());
        assertEquals("gt.gt_side.remind", viaLine.json().getAsJsonObject("data").get("task").getAsString());
        assertEquals("gt.gt_side.remind", viaScript.json().getAsJsonObject("data").getAsJsonObject("returned")
                .get("task").getAsString(), "派下的活叫脚本里的函数名");
    }

    /** 服务端那一侧把收到的 JSON 再读一遍:读不成就是三段的失败回执,处理函数不跑。 */
    @Test
    void theServerReadsTheCallAgainAndRefusesWhatDoesNotFit() {
        SERVER_CALLS.clear();
        String missing = serve("{\"after_s\":5}");
        assertTrue(missing.startsWith("argument 'reason' is missing (or nil)\nusage: gt.gt_side.remind(reason, "
                + "{after_s=…})"), missing);
        assertTrue(serve("{\"after_s\":\"soon\",\"reason\":\"x\"}")
                .startsWith("argument 'after_s': Expected integer"));
        assertTrue(serve("{\"after_s\":5,\"reason\":\"x\",\"when\":1}")
                .startsWith("unknown argument 'when'; this takes: reason, after_s"));
        assertTrue(SERVER_CALLS.isEmpty());
    }

    private static String serve(String json) {
        List<String> replies = new ArrayList<>();
        NumenCli.serve("gt gt_side remind", JsonParser.parseString(json).getAsJsonObject(), null, "test-call",
                replies::add);
        assertEquals(1, replies.size(), "恰好一次回执: " + replies);
        return JsonParser.parseString(replies.get(0)).getAsJsonObject().get("message").getAsString();
    }
}
