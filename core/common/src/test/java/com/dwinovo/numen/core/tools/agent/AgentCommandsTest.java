package com.dwinovo.numen.core.tools.agent;

import com.dwinovo.numen.agent.memory.NoteBook;
import com.dwinovo.numen.core.CoreScripts;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 主人客户端的札记函数,从模型的入口调:一段程序里的 {@code memory.*}。札记落在临时目录里。
 */
class AgentCommandsTest {

    @TempDir
    static Path homes;

    private static final UUID HER = UUID.randomUUID();

    @BeforeAll
    static void register() {
        NoteBook.init(uuid -> homes.resolve(uuid.toString()), () -> 7);
        // 照 NumenCore 同一份登记装上 core 的各组;登记时的报错(例子写不通、名字撞了)当场抛出
        com.dwinovo.numen.core.CoreCommandsFixture.install();
    }

    /** 跑一段程序,读它返回的那张表。 */
    private static JsonObject returned(String code) {
        CoreScripts.Run run = CoreScripts.run(HER, code);
        assertTrue(run.ok(), run.message());
        return run.receipt().getAsJsonObject("data").getAsJsonObject("returned");
    }

    @Test
    void aNoteIsWrittenReadBackAndDroppedFromAScript() {
        JsonObject wrote = returned("return numen.memory.remember(\"main base -340,68,120\", {name = \"main-base\", "
                + "type = \"world\", content = \"door faces east\"})");
        assertEquals("main-base", wrote.get("name").getAsString());

        JsonObject read = returned("return numen.memory.recall(\"main-base\")");
        assertEquals("door faces east", read.get("content").getAsString());
        assertTrue(NoteBook.of(HER).formatXml().contains("main base -340,68,120"),
                "the description is the index line: " + NoteBook.of(HER).formatXml());

        assertTrue(CoreScripts.run(HER, "numen.memory.forget(\"main-base\")").ok());
        CoreScripts.Run gone = CoreScripts.run(HER, "numen.memory.recall(\"main-base\")");
        assertFalse(gone.ok(), gone.message());
        assertTrue(gone.message().contains("no note named main-base"), gone.message());
    }

    /** 札记只有三种:写别的这一次调用就读不成,当场拒并列出能写的几个,什么都不落盘。 */
    @Test
    void aNoteOfAnUnknownTypeIsRefused() {
        CoreScripts.Run wrote = CoreScripts.run(HER, "numen.memory.remember(\"sweep the porch\", {name = \"chores\", "
                + "type = \"todo\"})");
        assertFalse(wrote.ok(), wrote.message());
        assertTrue(wrote.message().contains("expected one of owner, world, lesson"), wrote.message());
        assertTrue(NoteBook.of(HER).read("chores") == null, "a refused note was written anyway");
    }

    /** 写错了在主人客户端当场答,附上那个函数的用法。 */
    @Test
    void aMistakeAnswersWithTheFunctionsUsage() {
        CoreScripts.Run missing = CoreScripts.run(HER, "numen.memory.recall()");
        assertFalse(missing.ok());
        assertTrue(missing.message().contains("numen.memory.recall(name"), missing.message());
    }
}
