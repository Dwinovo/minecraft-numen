package com.dwinovo.numen.core.tools.agent;

import com.dwinovo.numen.agent.memory.NoteBook;
import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.agent.tool.ToolCall;
import com.dwinovo.numen.agent.tool.ToolRegistry;
import com.dwinovo.numen.api.NumenApi;
import com.dwinovo.numen.api.NumenPlugins;
import com.dwinovo.numen.cli.CommandTool;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 主人客户端的两组命令,从模型的入口调:{@code command} 工具的一行,或快捷工具 {@code load_skill}。札记落在临时目录里,
 * 技能表是空的(没装任何技能),所以装技能走的是"没有这份技能"那一支——同一个处理函数、同一份回执才是这里要钉的。
 */
class AgentCommandsTest {

    @TempDir
    static Path homes;

    private static final UUID HER = UUID.randomUUID();

    @BeforeAll
    static void register() {
        NoteBook.init(uuid -> homes.resolve(uuid.toString()), () -> 7);
        // 经插件那扇门登记,和 NumenCore 同一条路;登记时的报错(例子写不通、名字撞了)当场抛出
        AtomicReference<NumenApi> door = new AtomicReference<>();
        NumenPlugins.register(door::set);
        MemoryCommands.install(door.get());
        SkillCommands.install(door.get());
    }

    /** 调一次工具,收下它唯一的一条回执。 */
    private static JsonObject invoke(NumenTool tool, JsonObject args) {
        List<String> replies = new ArrayList<>();
        tool.invoke(new ToolCall("call-" + UUID.randomUUID(), tool.name(), args.toString(), () -> HER,
                replies::add));
        assertEquals(1, replies.size(), tool.name() + " " + args + " → " + replies);
        return JsonParser.parseString(replies.get(0)).getAsJsonObject();
    }

    private static JsonObject run(String line) {
        JsonObject args = new JsonObject();
        args.addProperty("command", line);
        return invoke(new CommandTool(), args);
    }

    @Test
    void aNoteIsWrittenReadBackAndDroppedFromTheCommandLine() {
        JsonObject wrote = run("memory remember main-base world \"main base -340,68,120\" --content \"door faces east\"");
        assertTrue(wrote.get("success").getAsBoolean(), wrote.toString());
        assertEquals("main-base", wrote.get("name").getAsString());

        JsonObject read = run("memory recall main-base");
        assertTrue(read.get("success").getAsBoolean(), read.toString());
        assertEquals("door faces east", read.get("content").getAsString());
        assertTrue(NoteBook.of(HER).formatXml().contains("main base -340,68,120"),
                "the description is the index line: " + NoteBook.of(HER).formatXml());

        assertTrue(run("memory forget main-base").get("success").getAsBoolean());
        JsonObject gone = run("memory recall main-base");
        assertFalse(gone.get("success").getAsBoolean(), gone.toString());
    }

    /** 札记只有三种:写别的这一行就写不通,当场拒并列出能写的几个,什么都不落盘。 */
    @Test
    void aNoteOfAnUnknownTypeIsRefused() {
        JsonObject wrote = run("memory remember chores todo \"sweep the porch\"");
        assertFalse(wrote.get("success").getAsBoolean(), wrote.toString());
        assertTrue(wrote.get("message").getAsString().contains("expected one of owner, world, lesson"), wrote.toString());
        assertTrue(NoteBook.of(HER).read("chores") == null, "a refused note was written anyway");
    }

    /** 写错了在主人客户端当场答,附上那个动作的用法。 */
    @Test
    void aMistakeAnswersWithTheActionsUsage() {
        JsonObject missing = run("memory recall");
        assertFalse(missing.get("success").getAsBoolean());
        assertTrue(missing.get("message").getAsString().contains("memory recall <name>"), missing.toString());
    }

    /**
     * 快捷工具 {@code load_skill} 就是 {@code skill load}:名字、参数、描述照旧(file 是可选的),同一次调用两个入口的
     * 回执一字不差。
     */
    @Test
    void loadSkillIsTheSameActionAsSkillLoad() {
        NumenTool tool = ToolRegistry.get("load_skill");
        assertTrue(tool.description().startsWith("Load a specialized skill when the task at hand matches"),
                tool.description());
        Map<String, Object> schema = tool.parameterSchema();
        assertEquals(List.of("name"), schema.get("required"), schema.toString());
        assertTrue(((Map<?, ?>) schema.get("properties")).containsKey("file"), schema.toString());

        JsonObject args = new JsonObject();
        args.addProperty("name", "no_such_skill");
        JsonObject viaTool = invoke(tool, args);
        JsonObject viaCommand = run("skill load no_such_skill");
        assertFalse(viaTool.get("success").getAsBoolean(), viaTool.toString());
        assertTrue(viaTool.toString().contains("unknown skill: no_such_skill"), viaTool.toString());
        assertEquals(viaTool, viaCommand);
    }
}
