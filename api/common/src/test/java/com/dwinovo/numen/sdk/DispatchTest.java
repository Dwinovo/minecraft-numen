package com.dwinovo.numen.sdk;

import com.dwinovo.numen.agent.script.ApiError;
import com.dwinovo.numen.agent.script.ErrorKind;
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

        @Fn("Say an argument does not hold right now.")
        @Example("gt.gt_echo.doubt(\"cake\")")
        public static void doubt(ClientCall call, Ask args) {
            throw new IllegalArgumentException(args.thing() + " is not a thing to doubt");
        }
    }

    @BeforeAll
    static void register() {
        SdkFixture.register("gt_echo", Echo.class);
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
}
