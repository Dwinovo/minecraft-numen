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
 * 写错了当场说错在哪,并附上那一层的用法;可选参数是 {@code --name value} 标志,顺序随意,写错的、写重的、
 * 缺值的各有一句话。
 */
class CommandParseTest {

    static final Param<Integer> COUNT = Param.required("count", ArgType.integer(1, 64), "How many.");
    static final Param<String> ITEM = Param.required("item", ArgType.word(), "Which item.");
    static final Param<String> FROM = Param.optional("from", ArgType.word(), "Where to take them from.");
    static final Param<Integer> LIMIT = Param.optional("limit", ArgType.integer(1, 10), "At most this many trips.");

    static final AtomicReference<CommandArgs> LAST = new AtomicReference<>();

    @BeforeAll
    static void register() {
        door().registerCommands("gt_parse", "A group the parser tests poke at.", g ->
                g.server("take", "Take some items.", (src, args) -> {
                    LAST.set(args);
                    src.reply(TaskResult.ok("took").toJson());
                }, COUNT, ITEM, FROM, LIMIT).example("numen gt_parse take 3 apple --from chest"));
    }

    private static final String TAKE_HELP = """
            numen gt_parse take <count> <item> [--from <word>] [--limit <integer>]
              Take some items.
              <count> (integer 1-64) — How many.
              <item> (word) — Which item.
              --from <word> (word; optional) — Where to take them from.
              --limit <integer> (integer 1-10; optional) — At most this many trips.
              Examples:
                numen gt_parse take 3 apple --from chest""";

    private static final String GROUP_HELP = """
            numen gt_parse: A group the parser tests poke at. Actions:
              numen gt_parse take <count> <item> [--from <word>] [--limit <integer>] — Take some items.
            numen gt_parse <action> --help explains one action.""";

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
        CommandArgs plain = ran("numen gt_parse take 3 apple");
        assertEquals(3, plain.get(COUNT));
        assertEquals("apple", plain.get(ITEM));
        assertNull(plain.get(FROM));
        assertNull(plain.get(LIMIT));

        CommandArgs flagged = ran("numen gt_parse take 3 apple --limit 2 --from chest");
        assertEquals("chest", flagged.get(FROM));
        assertEquals(2, flagged.get(LIMIT));
        assertEquals(flagged, ran("numen gt_parse take 3 apple --from chest --limit 2"), "标志顺序不影响读到的值");
    }

    @Test
    void aBadArgumentSaysWhatAndShowsTheActionsUsage() {
        String msg = failed("numen gt_parse take many apple");
        assertTrue(msg.startsWith("Expected integer at position 20: "), msg);
        assertTrue(msg.endsWith("\n" + TAKE_HELP), msg);

        String incomplete = failed("numen gt_parse take 3");
        assertTrue(incomplete.startsWith("Unknown command"), incomplete);
        assertTrue(incomplete.endsWith("\n" + TAKE_HELP), incomplete);
    }

    @Test
    void anUnknownActionOrAnUnfinishedLineShowsTheGroupsListing() {
        String typo = failed("numen gt_parse tke 3 apple");
        assertTrue(typo.startsWith("Unknown command at position 15: "), typo);
        assertTrue(typo.endsWith("\n" + GROUP_HELP), typo);

        String bare = failed("numen gt_parse");
        assertTrue(bare.startsWith("Unknown command"), bare);
        assertTrue(bare.endsWith("\n" + GROUP_HELP), bare);
    }

    @Test
    void aLineThatIsNotANumenCommandShowsTheRootListing() {
        String msg = failed("gt_parse take 3 apple");
        assertTrue(msg.contains("\nnumen <group> <action> [arguments]. Command groups:\n"), msg);
        String unknownGroup = failed("numen nosuchgroup take");
        assertTrue(unknownGroup.contains("\nnumen <group> <action> [arguments]. Command groups:\n"), unknownGroup);
    }

    @Test
    void flagMistakesEachSayWhatIsWrong() {
        String unknown = failed("numen gt_parse take 3 apple --form chest");
        assertTrue(unknown.startsWith("unknown flag --form; flags here: [--from <word>] [--limit <integer>]"),
                unknown);
        assertTrue(unknown.endsWith("\n" + TAKE_HELP), unknown);

        assertTrue(failed("numen gt_parse take 3 apple --from a --from b").startsWith("--from is given twice"));
        assertTrue(failed("numen gt_parse take 3 apple --from").startsWith("--from needs a value"));
        assertTrue(failed("numen gt_parse take 3 apple chest")
                .startsWith("expected a flag ([--from <word>] [--limit <integer>])"));
        assertTrue(failed("numen gt_parse take 3 apple --limit two").startsWith("Expected integer"),
                "标志的值用那个参数自己的类型读");
    }

    /**
     * 路由只有一条规则:解析到客户端动作才在客户端答。服务端动作写错了也原样送服务端,由那边的执行入口报——
     * 报的是上面那一句,附着这个动作的帮助。
     */
    @Test
    void aServerActionsMistakeIsReportedByTheServer() {
        CliFixture.Outcome client = onClient("numen gt_parse take many apple");
        assertTrue(client.forwarded, "服务端动作的一行整条送去服务端");
        assertTrue(client.replies.isEmpty(), "客户端不替服务端答");
        String server = failed("numen gt_parse take many apple");
        assertTrue(server.startsWith("Expected integer at position 20: "), server);
        assertTrue(server.endsWith("\n" + TAKE_HELP), server);
    }

    /** 客户端动作写错了在客户端当场回,说法和服务端动作写错时同一种:Brigadier 的原话加上这个动作的帮助。 */
    @Test
    void aClientActionsMistakeIsAnsweredRightThere() {
        Param<Integer> pages = Param.required("pages", ArgType.integer(1, 9), "How many pages.");
        door().registerCommands("gt_parse_local", "A group with an action on the owner's client.", g ->
                g.client("read", "Read some pages.", (src, args) -> src.reply(TaskResult.ok("read").toJson()), pages)
                        .example("numen gt_parse_local read 2"));
        CliFixture.Outcome client = onClient("numen gt_parse_local read many");
        assertFalse(client.forwarded, "客户端动作的解析错误当场回");
        assertFalse(client.success());
        assertTrue(client.message().startsWith("Expected integer at position 26: "), client.message());
        assertTrue(client.message().endsWith("""

                numen gt_parse_local read <pages>
                  Read some pages.
                  <pages> (integer 1-9) — How many pages.
                  Examples:
                    numen gt_parse_local read 2"""), client.message());
        assertEquals("read", onClient("numen gt_parse_local read 2").message());
    }
}
