package com.dwinovo.numen.cli;

import com.dwinovo.numen.task.TaskResult;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static com.dwinovo.numen.cli.CliFixture.door;
import static com.dwinovo.numen.cli.CliFixture.onServer;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 读的时候就认内容:{@link ArgType#as} 接在一种写法上的解读认不了,是这一行写错了,命令树当场报,处理函数不会被调到。
 */
class JudgedArgTest {

    /** 只认偶数的一个数:写法是整数,内容由这里认。 */
    static final ArgType<Integer> EVEN = ArgType.integer().as("even", "an even integer", n -> {
        if (n % 2 != 0) {
            throw new IllegalArgumentException(n + " is odd");
        }
        return n;
    }, n -> n);
    static final Param<Integer> SIZE = Param.required("size", EVEN, "An even size.");
    static final AtomicReference<CommandArgs> LAST = new AtomicReference<>();

    @BeforeAll
    static void register() {
        door().registerCommands("gt_judge", "A group whose values are judged as they are read.", g ->
                g.server("box", "A box.", (src, args) -> {
                    LAST.set(args);
                    src.reply(TaskResult.ok("box").toJson());
                }, SIZE).returns(com.dwinovo.numen.agent.script.ScriptType.NOTHING).example("gt_judge.box(4)"));
    }

    @Test
    void aValueTheJudgeRefusesIsAWrongLineBeforeTheHandlerRuns() {
        LAST.set(null);
        CliFixture.Outcome out = onServer("gt_judge box 3");
        assertFalse(out.success());
        assertTrue(out.message().startsWith("error: 3 is odd at position 13:"), "报错指在这个值的开头: " + out.message());
        assertEquals(null, LAST.get(), "处理函数不该被调到");
        IllegalArgumentException read = assertThrows(IllegalArgumentException.class,
                () -> NumenCli.read("gt_judge box 5"));
        assertTrue(read.getMessage().startsWith("error: 5 is odd"), "只读不执行的那一棵树认的是同一个解读: "
                + read.getMessage());
    }
}
