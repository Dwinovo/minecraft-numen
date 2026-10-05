package com.dwinovo.numen.sdk;

import com.dwinovo.numen.agent.script.ScriptCatalog;
import com.dwinovo.numen.agent.script.ScriptEngine;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 签名就是契约:一个 {@link Fn} 方法读成的函数——名字、在哪执行、怎么交回、参数的位置与选项、返回什么——全从签名来;以及登记时只拦的
 * 那几条硬错误,每条一个。
 */
class BinderTest {

    /** 一组签名齐全的函数。 */
    public static final class Shapes {

        private Shapes() {}

        public record Dig(@Doc("What to dig.") @Rest List<String> blocks,
                          @Doc("At most this many.") @Omitted("all of them") Optional<Integer> maxCount) {}

        public record Dug(int dug, Optional<String> note) {}

        @Fn("Dig some blocks.")
        @Example("gt.gt_shapes.dig(\"a\", \"b\", {max_count = 1})")
        public static Dug dig(ServerCall call, Dig args) {
            return new Dug(args.blocks().size(), Optional.empty());
        }

        public record Pick(@Doc("The item.") String item,
                           @Doc("How many.") @Omitted("one") @Positional Optional<Integer> count) {}

        @Fn("Pick some up.")
        public static Pending<Void> pick(ServerCall call, Pick args) {
            return Pending.of(null);
        }

        @Fn("Wait a while.")
        public static Job<Long> waitAWhile(ServerCall call) {
            return Job.done(0L);
        }

        @Fn("Jot a line on the owner's client.")
        public static String jot(ClientCall call) {
            return "";
        }
    }

    @BeforeAll
    static void register() {
        SdkFixture.register("gt_shapes", Shapes.class);
    }

    private static ApiFunction fn(String name) {
        return ApiRegistry.function("gt.gt_shapes." + name);
    }

    @Test
    void namesSidesAndKindsComeFromTheSignature() {
        assertEquals(List.of("dig", "jot", "pick", "wait_a_while"),
                ApiRegistry.group("gt.gt_shapes").functions().stream().map(ApiFunction::name).toList());
        assertEquals(ApiFunction.Side.SERVER, fn("dig").side());
        assertEquals(ApiFunction.Side.CLIENT, fn("jot").side());
        assertEquals(ScriptCatalog.Kind.VALUE, fn("dig").kind());
        assertEquals(ScriptCatalog.Kind.PENDING, fn("pick").kind());
        assertEquals(ScriptCatalog.Kind.JOB, fn("wait_a_while").kind());
        assertEquals("Dig some blocks.", fn("dig").summary());
        assertEquals(List.of("gt.gt_shapes.dig(\"a\", \"b\", {max_count = 1})"), fn("dig").examples());
    }

    @Test
    void componentsAreArgumentsInOrderAndOptionalOnesAreOptions() {
        List<ApiFunction.Param> dig = fn("dig").params();
        assertEquals(List.of("blocks", "max_count"), dig.stream().map(ApiFunction.Param::name).toList());
        assertEquals(List.of(ApiFunction.Role.REST, ApiFunction.Role.OPTION),
                dig.stream().map(ApiFunction.Param::role).toList());
        assertEquals("all of them", dig.get(1).omitted());
        List<ApiFunction.Param> pick = fn("pick").params();
        assertEquals(List.of(ApiFunction.Role.REQUIRED, ApiFunction.Role.OPTIONAL),
                pick.stream().map(ApiFunction.Param::role).toList());
        assertTrue(fn("wait_a_while").params().isEmpty());

        ScriptCatalog.Function script = fn("dig").script();
        assertEquals(Integer.MAX_VALUE, script.positions(), "the rest takes every object left");
        assertEquals(java.util.Set.of("max_count"), script.options());
        assertEquals(ScriptCatalog.Kind.VALUE, script.kind());
        assertEquals("gt.Dug", ScriptEngine.IN_USE.typeText(fn("dig").returns().type()), "a record is a class");
    }

    @Test
    void readArgumentsWriteBackTheSame() {
        Map<String, Object> named = new LinkedHashMap<>();
        named.put("blocks", List.of("a", "b"));
        named.put("max_count", 3L);
        Record args = fn("dig").decode(named);
        assertEquals(new Shapes.Dig(List.of("a", "b"), Optional.of(3)), args);
        assertEquals(named, fn("dig").encode(args));
        assertEquals("gt.gt_shapes.dig(\"a\", \"b\", {max_count = 3})", Call.of(fn("dig"), args));
    }

    // ---- 硬错误:每条一个 ----

    public static final class BadName {
        @Fn(value = "Bad.", name = "Not-A-Name")
        public static void act(ServerCall call) {
        }
    }

    @Test
    void aNameAScriptCannotWriteIsRefused() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> SdkFixture.register("gt_bad_name", BadName.class));
        assertTrue(e.getMessage().contains("is not a name a script can write"), e.getMessage());
    }

    public static final class KeywordName {
        @Fn(value = "Bad.", name = "end")
        public static void act(ServerCall call) {
        }
    }

    @Test
    void aKeywordOfTheLanguageIsRefused() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> SdkFixture.register("gt_keyword", KeywordName.class));
        assertTrue(e.getMessage().contains("'end' is a name Lua 5.2 already uses"), e.getMessage());
    }

    @Test
    void aNamespaceThatIsAGlobalOfTheLanguageIsRefused() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> ApiRegistry.register("string", "gt_string", "Test fixture.", Shapes.class));
        assertTrue(e.getMessage().startsWith("namespace "), e.getMessage());
    }

    public static final class Taken {
        @Fn("Taken.")
        public static void act(ServerCall call) {
        }
    }

    @Test
    void aGroupHasOneOwner() {
        SdkFixture.register("gt_taken", Taken.class);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> SdkFixture.register("gt_taken", Taken.class));
        assertTrue(e.getMessage().contains("the group gt.gt_taken already has an owner"), e.getMessage());
    }

    public static final class NoCall {
        @Fn("No call.")
        public static void act(String notACall) {
        }
    }

    @Test
    void theFirstArgumentIsTheCall() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> SdkFixture.register("gt_no_call", NoCall.class));
        assertTrue(e.getMessage().contains("takes (ServerCall call) or (ClientCall call)"), e.getMessage());
    }

    public static final class NotARecord {
        @Fn("Not a record.")
        public static void act(ServerCall call, String what) {
        }
    }

    @Test
    void theArgumentsAreARecord() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> SdkFixture.register("gt_not_record", NotARecord.class));
        assertTrue(e.getMessage().contains("its arguments are a record"), e.getMessage());
    }

    public static final class NoCodec {
        public record Args(@Doc("A thread.") Thread thread) {}

        @Fn("No codec.")
        public static void act(ServerCall call, Args args) {
        }
    }

    @Test
    void aTypeWithNoCodecIsRefused() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> SdkFixture.register("gt_no_codec", NoCodec.class));
        assertTrue(e.getMessage().contains("argument thread: no codec for java.lang.Thread"), e.getMessage());
    }

    public static final class PositionalInTheMiddle {
        public record Args(@Doc("Maybe.") @Positional Optional<Integer> maybe, @Doc("After.") String after) {}

        @Fn("Positional in the middle.")
        public static void act(ServerCall call, Args args) {
        }
    }

    @Test
    void anArgumentThatMayBeLeftOutIsTheLastPositionalOne() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> SdkFixture.register("gt_positional", PositionalInTheMiddle.class));
        assertTrue(e.getMessage().contains("after comes after the argument that may be left out"), e.getMessage());
    }

    /** 两个不同的 record 叫同一个名字:脚本里的类名撞了。 */
    public static final class First {
        public record Clash(int a) {}

        @Fn("First.")
        public static Clash act(ServerCall call) {
            return new Clash(1);
        }
    }

    public static final class Second {
        public record Clash(String b) {}

        @Fn("Second.")
        public static Clash act(ServerCall call) {
            return new Clash("b");
        }
    }

    @Test
    void twoTypesCannotShareAClassName() {
        SdkFixture.register("gt_clash_one", First.class);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> SdkFixture.register("gt_clash_two", Second.class));
        assertTrue(e.getMessage().contains("the Lua class name gt.Clash is taken"), e.getMessage());
    }
}
