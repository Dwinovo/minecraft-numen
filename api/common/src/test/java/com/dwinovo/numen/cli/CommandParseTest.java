package com.dwinovo.numen.cli;

import com.dwinovo.numen.task.TaskResult;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static com.dwinovo.numen.cli.CliFixture.door;
import static com.dwinovo.numen.cli.CliFixture.onClient;
import static com.dwinovo.numen.cli.CliFixture.onServer;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 写错了当场说清楚,三段:{@code error:} 错在哪,{@code usage:} 那一层的正确写法(动作这一层是用法行加例子),{@code hint:}
 * 能照抄的下一步;标志写成 {@code --name value},顺序随意,写错的、写重的、缺值的各有一句话。
 */
class CommandParseTest {

    static final Param<Integer> COUNT = Param.required("count", ArgType.integer(1, 64), "How many.");
    static final Param<String> ITEM = Param.optional("item", ArgType.word(), "Which item.")
            .whenOmitted("take whatever is there");
    static final Param<String> FROM = Param.optional("from", ArgType.word(), "Where to take them from.")
            .whenOmitted("take them from the nearest chest");
    static final Param<Integer> LIMIT = Param.optional("limit", ArgType.integer(1, 10), "At most this many trips.")
            .whenOmitted("go as often as it takes");

    static final AtomicReference<CommandArgs> LAST = new AtomicReference<>();

    @BeforeAll
    static void register() {
        door().registerCommands("gt_parse", "A group the parser tests poke at.", g ->
                g.server("take", "Take some items.", (src, args) -> {
                    LAST.set(args);
                    src.reply(TaskResult.ok("took").toJson());
                }, COUNT, ITEM, FROM, LIMIT).example("gt_parse take 3 --item apple --from chest"));
    }

    /** {@code usage:} 那一段:用法行接例子。 */
    private static final String TAKE_USAGE = """
            usage: gt_parse take <count> [--item <word>] [--from <word>] [--limit <integer>]
              e.g. gt_parse take 3 --item apple --from chest""";
    private static final String TAKE_HINT = "hint: `gt_parse take --help` explains every argument.";

    private static final String GROUP_USAGE = """
            usage: gt_parse: A group the parser tests poke at. Actions:
              gt_parse take <count> [--item <word>] [--from <word>] [--limit <integer>] — Take some items.
            gt_parse <action> --help explains one action.""";

    private static CommandArgs ran(String line) {
        LAST.set(null);
        CliFixture.Outcome out = onServer(line);
        assertTrue(out.success(), out.message());
        return LAST.get();
    }

    private static String failed(String line) {
        LAST.set(null);
        CliFixture.Outcome out = onServer(line);
        assertFalse(out.success(), line + " should fail");
        assertNull(LAST.get(), "处理函数不该被调到");
        return out.message();
    }

    @Test
    void flagsComeInAnyOrderAndMissingOnesAreNull() {
        CommandArgs plain = ran("gt_parse take 3");
        assertEquals(3, plain.get(COUNT));
        assertNull(plain.get(ITEM));
        assertNull(plain.get(FROM));
        assertNull(plain.get(LIMIT));

        CommandArgs flagged = ran("gt_parse take 3 --item apple --limit 2 --from chest");
        assertEquals("apple", flagged.get(ITEM));
        assertEquals("chest", flagged.get(FROM));
        assertEquals(2, flagged.get(LIMIT));
        assertEquals(flagged, ran("gt_parse take 3 --from chest --limit 2 --item apple"), "标志顺序不影响读到的值");
    }

    @Test
    void aBadArgumentSaysWhatAndShowsTheActionsUsage() {
        String msg = failed("gt_parse take many");
        assertTrue(msg.startsWith("error: Expected integer at position 14: "), msg);
        assertTrue(msg.endsWith("\n" + TAKE_USAGE + "\n" + TAKE_HINT), msg);

        String incomplete = failed("gt_parse take");
        assertTrue(incomplete.startsWith("error: Unknown command"), incomplete);
        assertTrue(incomplete.endsWith("\n" + TAKE_USAGE + "\n" + TAKE_HINT), incomplete);
    }

    @Test
    void anUnknownActionOrAnUnfinishedLineShowsTheGroupsListing() {
        String typo = failed("gt_parse tke 3");
        assertTrue(typo.startsWith("error: Unknown command at position 9: "), typo);
        assertTrue(typo.endsWith("\n" + GROUP_USAGE + "\nhint: Did you mean: take?"),
                "那一层的用法之后,下一步是最接近的动作: " + typo);

        String bare = failed("gt_parse");
        assertTrue(bare.startsWith("error: Unknown command"), bare);
        assertTrue(bare.endsWith("\n" + GROUP_USAGE + "\nhint: `gt_parse --help` explains each action."), bare);
    }

    @Test
    void aLineOutsideEveryGroupShowsTheRootListing() {
        String prefixed = failed("numen gt_parse take 3");
        assertTrue(prefixed.startsWith("error: Unknown command at position 0: "), prefixed);
        assertTrue(prefixed.contains("\nusage: <group> <action> [arguments]. Command groups:\n"), prefixed);
        String unknownGroup = failed("nosuchgroup take");
        assertTrue(unknownGroup.contains("\nusage: <group> <action> [arguments]. Command groups:\n"), unknownGroup);
        assertTrue(unknownGroup.endsWith("\nhint: `help` lists the command groups."), unknownGroup);
    }

    @Test
    void flagMistakesEachSayWhatIsWrong() {
        String unknown = failed("gt_parse take 3 --form chest");
        assertTrue(unknown.startsWith("error: unknown flag --form; flags here: [--item <word>] [--from <word>] "
                + "[--limit <integer>]"), unknown);
        assertTrue(unknown.endsWith("\n" + TAKE_USAGE + "\n" + TAKE_HINT), unknown);

        assertTrue(failed("gt_parse take 3 --from a --from b").startsWith("error: --from is given twice"));
        assertTrue(failed("gt_parse take 3 --from").startsWith("error: --from needs a value"));
        assertTrue(failed("gt_parse take 3 chest")
                .startsWith("error: expected a flag ([--item <word>] [--from <word>] [--limit <integer>])"));
        assertTrue(failed("gt_parse take 3 --limit two").startsWith("error: Expected integer"),
                "标志的值用那个参数自己的类型读");
    }

    /**
     * 路由只有一条规则:解析到客户端动作才在客户端答。服务端动作写错了也原样送服务端,由那边的执行入口报——
     * 报的是上面那一句,附着这个动作的用法。
     */
    @Test
    void aServerActionsMistakeIsReportedByTheServer() {
        CliFixture.Outcome client = onClient("gt_parse take many");
        assertTrue(client.forwarded, "服务端动作的一行整条送去服务端");
        assertTrue(client.replies.isEmpty(), "客户端不替服务端答");
        String server = failed("gt_parse take many");
        assertTrue(server.startsWith("error: Expected integer at position 14: "), server);
        assertTrue(server.endsWith("\n" + TAKE_USAGE + "\n" + TAKE_HINT), server);
    }

    /** 客户端动作写错了在客户端当场回,说法和服务端动作写错时同一种。 */
    @Test
    void aClientActionsMistakeIsAnsweredRightThere() {
        Param<Integer> pages = Param.required("pages", ArgType.integer(1, 9), "How many pages.");
        door().registerCommands("gt_parse_local", "A group with an action on the owner's client.", g ->
                g.client("read", "Read some pages.", (src, args) -> src.reply(TaskResult.ok("read").toJson()), pages)
                        .example("gt_parse_local read 2"));
        CliFixture.Outcome client = onClient("gt_parse_local read many");
        assertFalse(client.forwarded, "客户端动作的解析错误当场回");
        assertFalse(client.success());
        assertTrue(client.message().startsWith("error: Expected integer at position 20: "), client.message());
        assertTrue(client.message().endsWith("""

                usage: gt_parse_local read <pages>
                  e.g. gt_parse_local read 2
                hint: `gt_parse_local read --help` explains every argument."""), client.message());
        assertEquals("read", onClient("gt_parse_local read 2").message());
    }
}
