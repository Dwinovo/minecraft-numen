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
 * 读的时候就认内容:{@link ArgType#as} 接在一种写法上的解读认不了,是这一行写错了,命令树当场报,处理函数不会被调到;
 * {@link ArgType#command} 的值是本组的另一行命令,由读外面那一行的同一棵树读——里面写错了一样报,里面的值一样认。
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
    static final Param<NumenCli.Reading> THEN = Param.required("then", ArgType.command(), "Another line of the group.");
    static final AtomicReference<CommandArgs> LAST = new AtomicReference<>();

    @BeforeAll
    static void register() {
        door().registerCommands("gt_judge", "A group whose values are judged as they are read.", g -> {
            g.server("box", "A box.", (src, args) -> {
                LAST.set(args);
                src.reply(TaskResult.ok("box").toJson());
            }, SIZE).example("gt_judge box 4");
            g.server("later", "Run another line of this group later.", (src, args) -> {
                LAST.set(args);
                src.reply(TaskResult.ok("later").toJson());
            }, THEN).example("gt_judge later box 6");
        });
    }

    @Test
    void aValueTheJudgeRefusesIsAWrongLineBeforeTheHandlerRuns() {
        LAST.set(null);
        CliFixture.Outcome out = onServer("gt_judge box 3");
        assertFalse(out.success());
        assertTrue(out.message().startsWith("3 is odd at position 13:"), "报错指在这个值的开头: " + out.message());
        assertEquals(null, LAST.get(), "处理函数不该被调到");
        IllegalArgumentException read = assertThrows(IllegalArgumentException.class,
                () -> NumenCli.read("gt_judge box 5"));
        assertTrue(read.getMessage().startsWith("5 is odd"), "只读不执行的那一棵树认的是同一个解读: " + read.getMessage());
    }

    @Test
    void aLineInsideALineIsReadByTheSameTree() {
        NumenCli.Reading outer = NumenCli.read("gt_judge later box 6");
        NumenCli.Reading inner = outer.args().get(THEN);
        assertEquals("gt_judge box", inner.path());
        assertEquals(6, inner.args().get(SIZE));
        assertEquals("gt_judge later box 6", outer.args().write(outer.path(), java.util.List.of(THEN)),
                "里面那一行写回的是组名之后的那一截");

        IllegalArgumentException odd = assertThrows(IllegalArgumentException.class,
                () -> NumenCli.read("gt_judge later box 7"));
        assertTrue(odd.getMessage().startsWith("7 is odd"), "里面那一行的值同样认: " + odd.getMessage());
        IllegalArgumentException typo = assertThrows(IllegalArgumentException.class,
                () -> NumenCli.read("gt_judge later bx 6"));
        assertTrue(typo.getMessage().contains("Did you mean: box?"), "里面那一行写错了,报错和单独执行时一样: "
                + typo.getMessage());

        LAST.set(null);
        assertTrue(onServer("gt_judge later box 8").success());
        assertEquals(8, LAST.get().get(THEN).args().get(SIZE), "执行时处理函数拿到的是读好的那一行");
    }

    @Test
    void aLineInsideALineCannotBecomeAQuickTool() {
        assertThrows(IllegalArgumentException.class, () -> door().registerCommands("gt_judge_tool",
                "A group that tries to promote a line-typed action.", g -> g.server("later", "Later.",
                        (src, args) -> src.reply(TaskResult.ok("later").toJson()), THEN)
                        .example("gt_judge_tool later later later")
                        .promote("Runs a line later.")));
    }
}
