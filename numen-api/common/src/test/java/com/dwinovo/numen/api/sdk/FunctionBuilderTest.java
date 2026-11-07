package com.dwinovo.numen.api.sdk;

import com.dwinovo.numen.api.agent.scope.Handle;
import com.dwinovo.numen.script.ScriptCatalog;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 用代码写的函数描述和反射读出来的是同一种东西:登记、派发、帮助、体检分不出来源;撤回登记之后它不再在表里。
 */
class FunctionBuilderTest {

    static {
        SdkFixture.ready();
    }

    private static Codec<?> codec(Class<?> type) {
        return LuaCodecs.of(type, SdkFixture.NAMESPACE);
    }

    private static ApiFunction greet() {
        return ApiFunction.builder("greet")
                .summary("Say hello to someone by name.")
                .required("who", codec(String.class), "The name.")
                .option("times", codec(Integer.class), "How many times.", "say it once")
                .returns(codec(String.class))
                .example("gt.gt_built.greet(\"Steve\", {times = 2})")
                .client((call, args) -> (args.<String>get("who") + " ").repeat(args.<Integer>option("times").orElse(1)).strip());
    }

    private static ApiTester.Run run(String code) {
        return ApiTester.run(null, UUID.randomUUID(), code);
    }

    @Test
    void aBuiltFunctionIsRegisteredDocumentedAndCalledLikeAnyOther() {
        Handle handle = ApiRegistry.register(SdkFixture.NAMESPACE, "gt_built", "Test fixture.", List.of(greet()));
        try {
            ApiFunction fn = ApiRegistry.function("gt.gt_built.greet");
            assertNotNull(fn);
            assertEquals("gt.gt_built.greet", fn.fullName());
            assertEquals(ApiFunction.Side.CLIENT, fn.side());
            assertEquals(ScriptCatalog.Kind.VALUE, fn.kind());
            assertEquals(List.of(ApiFunction.Role.REQUIRED, ApiFunction.Role.OPTION),
                    fn.params().stream().map(ApiFunction.Param::role).toList());
            assertTrue(ApiDocs.usage(fn).contains("who"), ApiDocs.usage(fn));
            assertEquals("How many times. Omit to say it once.", fn.params().get(1).explained());

            ApiTester.Run run = run("return gt.gt_built.greet(\"Steve\", {times = 2})");
            assertTrue(run.ok(), run.message());
            assertEquals("Steve Steve", run.returned().getAsString());

            ApiTester.Run bad = run("gt.gt_built.greet(3)");
            assertFalse(bad.ok());
            assertTrue(bad.message().contains("bad_argument"), bad.message());
            assertTrue(bad.message().contains("argument 'who'"), bad.message());
        } finally {
            handle.close();
        }
    }

    @Test
    void closingTheHandleTakesTheGroupOutOfEveryTableAndItCanComeBack() {
        Handle first = ApiRegistry.register(SdkFixture.NAMESPACE, "gt_gone", "Test fixture.", List.of(greet()));
        assertNotNull(ApiRegistry.group("gt.gt_gone"));
        first.close();
        assertNull(ApiRegistry.group("gt.gt_gone"));
        assertNull(ApiRegistry.function("gt.gt_gone.greet"));
        assertTrue(ApiRegistry.functions().stream().noneMatch(f -> f.groupName().equals("gt.gt_gone")));
        first.close();

        Handle again = ApiRegistry.register(SdkFixture.NAMESPACE, "gt_gone", "Test fixture.", List.of(greet()));
        try {
            assertNotNull(ApiRegistry.function("gt.gt_gone.greet"));
        } finally {
            again.close();
        }
    }

    @Test
    void aBuilderOnlyStopsWhatBreaksTheSystem() {
        assertThrows(IllegalArgumentException.class, () -> ApiFunction.builder("Bad-Name").client((c, a) -> null));
        IllegalArgumentException order = assertThrows(IllegalArgumentException.class, () -> ApiFunction.builder("f")
                .positional("maybe", codec(String.class), "May be left out.", "skip it")
                .required("after", codec(String.class), "Comes after.")
                .client((c, a) -> null));
        assertTrue(order.getMessage().contains("after comes after the argument that may be left out"), order.getMessage());
        assertThrows(IllegalArgumentException.class, () -> ApiFunction.builder("f").kind(ScriptCatalog.Kind.PENDING)
                .client((c, a) -> null), "a client function answers at once");

        // 没写说明、没写例子都能登记:那是 lint 的事
        ApiFunction bare = ApiFunction.builder("bare").client((c, a) -> null);
        assertEquals("", bare.summary());
        assertTrue(bare.examples().isEmpty());
        assertThrows(IllegalStateException.class, bare::fullName, "no owner until it is registered");
    }

    @Test
    void aGroupNeedsFunctionsAndNoNameTwice() {
        assertThrows(IllegalArgumentException.class,
                () -> ApiRegistry.register(SdkFixture.NAMESPACE, "gt_empty", "Test fixture.", List.<ApiFunction>of()));
        IllegalArgumentException twice = assertThrows(IllegalArgumentException.class,
                () -> ApiRegistry.register(SdkFixture.NAMESPACE, "gt_twice", "Test fixture.", List.of(greet(), greet())));
        assertTrue(twice.getMessage().contains("greet twice"), twice.getMessage());
        assertNull(ApiRegistry.group("gt.gt_twice"));
    }
}
