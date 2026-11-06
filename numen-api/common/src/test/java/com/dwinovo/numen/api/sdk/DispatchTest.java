package com.dwinovo.numen.api.sdk;

import com.dwinovo.numen.script.ApiError;
import com.dwinovo.numen.script.ErrorKind;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 一段程序从脚本到函数再回到脚本:位置参数按顺序、收下余下全部的那一个、最后的选项表;参数读不成的在调用处失败并附用法与帮助的
 * 写法,不派出去;函数说的失败原样成为脚本里的错误值(种类、那句话、下一步、数据);交回的值按返回类型写成脚本的值。函数都在主人客户端,
 * 不要世界。
 */
class DispatchTest {

    /** 一组主人客户端上的函数。 */
    public static final class Echo {

        private Echo() {}

        public record Say(@Doc("What to say.") String text,
                          @Doc("How many times.") @Omitted("once") @Positional Optional<Integer> times,
                          @Doc("Say it loud.") @Omitted("quietly") Optional<Boolean> loud) {}

        public record Said(String text, int times, boolean loud) {}

        @Fn("Say something back.")
        @Example("gt.gt_echo.say(\"hi\", 2, {loud = true})")
        public static Said say(ClientCall call, Say args) {
            return new Said(args.text(), args.times().orElse(1), args.loud().orElse(false));
        }

        public record Count(@Doc("The words.") @Rest List<String> words) {}

        @Fn("Count the words.")
        @Example("gt.gt_echo.count(\"a\", \"b\")")
        public static int count(ClientCall call, Count args) {
            return args.words().size();
        }

        public record Mark(@Doc("The cells.") @Rest List<BlockPos> cells) {}

        @Fn("Mark some cells.")
        @Example("gt.gt_echo.mark({x = 1, y = 2, z = 3})")
        public static int mark(ClientCall call, Mark args) {
            return args.cells().size();
        }

        public record Ask(@Doc("What it asks for.") String thing) {}

        @Fn("Fail the way a function fails.")
        @Example("gt.gt_echo.refuse(\"cake\")")
        public static void refuse(ClientCall call, Ask args) {
            throw new ApiError(ErrorKind.NOT_FOUND, "no " + args.thing() + " here",
                    Call.of("gt.gt_echo.say", "where is the " + args.thing() + "?"), Map.of("looked", 3L));
        }

        public record Size(@Doc("How many characters.") int size) {}

        @Fn("Answer with a text this long.")
        @Example("gt.gt_echo.big(10)")
        public static String big(ClientCall call, Size args) {
            return "x".repeat(args.size());
        }

        @Fn("Say an argument does not hold right now.")
        @Example("gt.gt_echo.doubt(\"cake\")")
        public static void doubt(ClientCall call, Ask args) {
            throw new IllegalArgumentException(args.thing() + " is not a thing to doubt");
        }
    }

    /** 一组服务端上的函数。 */
    public static final class Wait {

        private Wait() {}

        @Fn("Wait for a Said, and hand back something else.")
        @Example("gt.gt_wait.mislabel(\"cake\")")
        @SuppressWarnings({"unchecked", "rawtypes"})
        public static Pending<Echo.Said> mislabel(ServerCall call, Echo.Ask args) {
            return (Pending) Pending.of(args.thing());
        }

        @Fn("Count the cells handed over.")
        @Example("gt.gt_wait.tally({x = 1, y = 2, z = 3})")
        public static int tally(ServerCall call, Echo.Mark args) {
            return args.cells().size();
        }

        @Fn("Hand back a value longer than one packet to the client.")
        @Example("gt.gt_wait.flood()")
        public static String flood(ServerCall call) {
            return "x".repeat(com.dwinovo.numen.api.network.Wire.TO_CLIENT.bytes() + 1);
        }
    }

    @BeforeAll
    static void register() {
        SdkFixture.register("gt_echo", Echo.class);
        SdkFixture.register("gt_wait", Wait.class);
    }

    /** 客户端函数交回的结果超过一个上行的包:答复分片送回服务端(回环同样过分片),程序拿到的是完整的文字。 */
    @Test
    void aClientAnswerLongerThanOneUpwardPacketComesBackWhole() {
        int size = 10 * com.dwinovo.numen.api.network.Wire.TO_SERVER.bytes();
        ApiTester.Run run = run("return #gt.gt_echo.big(" + size + ")");
        assertTrue(run.ok(), run.message());
        assertEquals(size, run.returned().getAsInt());
    }

    /**
     * 上千格整串交给服务端的函数:程序跑在服务端,实参不经网线,不受一个上行包的大小管(一千格写成文字远过 32 KB),函数收到的是整串。
     */
    @Test
    void aThousandCellsGoIntoAServerFunctionWhole() {
        ApiTester.Run run = run("""
                local all = {}
                for x = 1, 32 do
                  for z = 1, 32 do
                    all[#all + 1] = {x = 100000 + x, y = 64, z = -100000 - z}
                  end
                end
                return gt.gt_wait.tally(all)
                """);
        assertTrue(run.ok(), run.message());
        assertEquals(1024, run.returned().getAsInt());
    }

    /**
     * 失败的调用即使被 pcall 接住也写进 stderr(同 Unix);没接住的,停在哪一行是结局,stderr 里还是那一条。
     */
    @Test
    void aFailedCallIsOnStderrWhetherOrNotTheProgramCaughtIt() {
        ApiTester.Run caught = run("local ok, err = pcall(gt.gt_echo.refuse, \"cake\")\nprint(ok, err.kind)");
        assertTrue(caught.ok() && caught.message().startsWith("ok · 1 call · "), caught.message());
        assertTrue(caught.message().contains("\nstderr:\nline 1 gt.gt_echo.refuse: not_found — ")
                && caught.message().contains("\nstdout:\nfalse\tnot_found"), caught.message());

        ApiTester.Run uncaught = run("local r = gt.gt_echo.say(\"hi\")\ngt.gt_echo.refuse(\"cake\")\n");
        String stopped = uncaught.message();
        assertFalse(uncaught.ok());
        assertTrue(stopped.startsWith("The script stopped at line 2 after 2 calls: gt.gt_echo.refuse: not_found — ")
                && stopped.contains("\nhint: ") && stopped.contains("\nstderr:\nline 2 gt.gt_echo.refuse: not_found — "), stopped);
    }

    /**
     * 程序 return 一个大值:模型读到的只有回执的文字(缩略过的那份,成败在第一行);原值整团只在服务端进程里(结局对象),不在回执里。
     */
    @Test
    void aBigReturnedValueReachesTheModelOnlyAsTheShortenedMessage() {
        ApiTester.Run run = run("gt.gt_echo.count(\"a\")\nlocal t = {}\nfor i = 1, 950 do t[i] = i end\nreturn t");
        assertTrue(run.ok(), run.message());
        assertEquals(950, run.returned().getAsJsonArray().size(), "the program lost the raw value");
        String receipt = run.outcome().receipt();
        String heard = com.dwinovo.numen.api.agent.llm.ToolOutcome.modelText(receipt);
        assertTrue(heard.startsWith("ok · 1 call · ") && heard.contains("…(950 items in all;")
                && !receipt.contains("\"data\"") && !receipt.contains("\"returned\""), receipt);
    }

    /**
     * 一次调用交回的值比一个下行包大:程序跑在服务端,值只在服务端里传,程序照样跑完;回执是给模型读的,按构造有界——返回值截到上限,
     * 写明原来多长。
     */
    @Test
    void aValueLongerThanAPacketStaysOnTheServerAndTheReceiptIsBounded() {
        ApiTester.Run run = run("return gt.gt_wait.flood()");
        assertTrue(run.ok(), run.message().substring(0, Math.min(400, run.message().length())));
        assertEquals(com.dwinovo.numen.api.network.Wire.TO_CLIENT.bytes() + 1, run.returned().getAsString().length());
        assertTrue(run.outcome().receipt().length() < com.dwinovo.numen.script.ScriptLimits.RETURNED_CHARS + 500,
                "the receipt is " + run.outcome().receipt().length() + " characters");
        assertTrue(run.message().contains("[returned value cut at " + com.dwinovo.numen.script.ScriptLimits.RETURNED_CHARS
                + " characters; it was " + (com.dwinovo.numen.api.network.Wire.TO_CLIENT.bytes() + 1) + "]"), "not said");
    }

    private static ApiTester.Run run(String code) {
        return ApiTester.run(null, UUID.randomUUID(), code);
    }

    @Test
    void positionalArgumentsThenTheOptionsTable() {
        ApiTester.Run run = run("local r = gt.gt_echo.say(\"hi\", 2, {loud = true})\nreturn r.text .. r.times .. tostring(r.loud)");
        assertTrue(run.ok(), run.message());
        assertEquals("hi2true", run.returned().getAsString());
        assertEquals("hi1false", run("local r = gt.gt_echo.say(\"hi\")\nreturn r.text .. r.times .. tostring(r.loud)")
                .returned().getAsString());
    }

    @Test
    void theRestTakesEveryObjectLeftOrOneList() {
        assertEquals(3, run("return gt.gt_echo.count(\"a\", \"b\", \"c\")").returned().getAsInt());
        assertEquals(3, run("return gt.gt_echo.count({\"a\", \"b\", \"c\"})").returned().getAsInt());
        assertEquals(1, run("return gt.gt_echo.count(\"a\")").returned().getAsInt());
    }

    @Test
    void aCellWrittenAsThreeNumbersIsRefusedWithTheCallRewritten() {
        String rewritten = "gt.gt_echo.mark({x = 1, y = 2, z = 3})";
        for (String written : List.of("{1, 2, 3}", "\"1 2 3\"")) {
            ApiTester.Run run = run("local ok, err = pcall(gt.gt_echo.mark, " + written + ")\nreturn err.kind .. \"|\" .. err.hint");
            assertEquals("bad_argument|" + rewritten, run.returned().getAsString(), written);
        }
        assertEquals(2, run("return gt.gt_echo.mark({x = 1, y = 2, z = 3}, {x = 4, y = 5, z = 6})").returned().getAsInt());
    }

    @Test
    void anArgumentThatDoesNotReadFailsAtItsLineWithTheUsageAndIsNotSent() {
        ApiTester.Run run = run("gt.gt_echo.say(\"hi\", \"twice\")\nreturn 1");
        assertFalse(run.ok());
        assertTrue(run.replies().isEmpty(), "a call that does not read was sent: " + run.replies());
        assertTrue(run.message().contains("stopped at line 1"), run.message());
        assertTrue(run.message().contains("bad_argument"), run.message());
        assertTrue(run.message().contains("argument 'times'"), run.message());
    }

    @Test
    void aFunctionsFailureIsTheScriptsErrorValue() {
        ApiTester.Run run = run("""
                local ok, err = pcall(gt.gt_echo.refuse, "cake")
                return {ok = ok, kind = err.kind, message = err.message, hint = err.hint, looked = err.data.looked}""");
        assertTrue(run.ok(), run.message());
        JsonObject got = run.returned().getAsJsonObject();
        assertFalse(got.get("ok").getAsBoolean());
        assertEquals("not_found", got.get("kind").getAsString());
        assertEquals("no cake here", got.get("message").getAsString());
        assertEquals("gt.gt_echo.say(\"where is the cake?\")", got.get("hint").getAsString());
        assertEquals(3, got.get("looked").getAsInt());
    }

    @Test
    void anArgumentTheFunctionSaysDoesNotHoldIsABadArgumentWithTheUsage() {
        ApiTester.Run run = run("gt.gt_echo.doubt(\"cake\")");
        assertFalse(run.ok());
        JsonObject reply = run.lastReply();
        JsonObject error = reply.getAsJsonObject("error");
        assertEquals("bad_argument", error.get("kind").getAsString());
        assertTrue(error.get("message").getAsString().startsWith("cake is not a thing to doubt\nusage: "),
                error.toString());
        assertEquals("print(numen.api.help(\"gt.gt_echo.doubt\"))", error.get("hint").getAsString());
    }

    @Test
    void aFunctionThatIsNotThereNamesTheNearestOne() {
        ApiTester.Run run = run("gt.gt_echo.counts(\"hi\")");
        assertFalse(run.ok());
        assertTrue(run.message().contains("there is no API function gt.gt_echo.counts; did you mean "
                + "gt.gt_echo.count?"), run.message());
    }

    @Test
    void aValueTheFunctionsSignatureCannotWriteIsOneFailedReplyNotAnExceptionOutOfTheCall() {
        JsonObject args = new JsonObject();
        args.addProperty("thing", "cake");
        List<String> replies = new java.util.ArrayList<>();
        Dispatcher.serve("gt.gt_wait.mislabel", args, null, java.util.UUID.randomUUID(), "c1", replies::add);
        assertEquals(1, replies.size(), replies.toString());
        JsonObject error = com.google.gson.JsonParser.parseString(replies.get(0)).getAsJsonObject().getAsJsonObject("error");
        assertEquals("failed", error.get("kind").getAsString());
        assertTrue(error.get("message").getAsString().startsWith("gt.gt_wait.mislabel broke: "), error.toString());
    }
}
