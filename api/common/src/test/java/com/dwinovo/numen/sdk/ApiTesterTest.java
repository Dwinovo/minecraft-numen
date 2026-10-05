package com.dwinovo.numen.sdk;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 写法上的问题不在登记时拦,在 {@link ApiTester#lint} 的报告里:没写说明、没写例子、例子读不通或没调到自己、相关函数不存在;文字里写着
 * 的调用读不通、点名的函数不存在。写得好的不报。
 */
class ApiTesterTest {

    /** 写得马虎的一组。 */
    public static final class Sloppy {

        private Sloppy() {}

        public record Args(String what, @Doc("How many.") int count) {}

        @Fn("Do it.")
        @SeeAlso("gt.gt_sloppy.nothing_here")
        public static void bare(ServerCall call, Args args) {
        }

        @Fn("Do it again.")
        @Example("gt.gt_sloppy.again(")
        @Example("gt.gt_sloppy.bare(\"x\", 1)")
        @Example("gt.gt_sloppy.again(\"x\", \"two\")")
        public static void again(ServerCall call, Args args) {
        }
    }

    /** 写得好的一组。 */
    public static final class Tidy {

        private Tidy() {}

        public record Args(@Doc("What to do.") String what) {}

        @Fn("Do it neatly.")
        @Example("gt.gt_tidy.neat(\"x\")")
        @SeeAlso("gt.gt_sloppy.bare")
        public static void neat(ServerCall call, Args args) {
        }
    }

    @BeforeAll
    static void register() {
        SdkFixture.register("gt_sloppy", Sloppy.class);
        SdkFixture.register("gt_tidy", Tidy.class);
    }

    private static List<String> about(String fn) {
        return ApiTester.lint().stream().filter(l -> l.where().equals(fn)).map(ApiTester.Lint::problem).toList();
    }

    @Test
    void aSloppyFunctionIsReportedNotRefused() {
        List<String> bare = about("gt.gt_sloppy.bare");
        assertTrue(bare.contains("argument what has no @Doc"), bare.toString());
        assertTrue(bare.stream().anyMatch(p -> p.startsWith("no @Example")), bare.toString());
        assertTrue(bare.contains("@SeeAlso names gt.gt_sloppy.nothing_here, which is no function"), bare.toString());

        List<String> again = about("gt.gt_sloppy.again");
        assertTrue(again.stream().anyMatch(p -> p.startsWith("the example `gt.gt_sloppy.again(` does not read")),
                again.toString());
        assertTrue(again.contains("the example `gt.gt_sloppy.bare(\"x\", 1)` does not call gt.gt_sloppy.again"),
                again.toString());
        assertTrue(again.stream().anyMatch(p -> p.startsWith("the example `gt.gt_sloppy.again(\"x\", \"two\")` calls "
                + "gt.gt_sloppy.again wrongly: argument 'count'")), again.toString());
    }

    @Test
    void aTidyFunctionIsNotReported() {
        assertEquals(List.of(), about("gt.gt_tidy.neat"));
    }

    @Test
    void callsWrittenInTextAreRead() {
        List<ApiTester.Lint> lint = ApiTester.lint(List.of(new ApiTester.Text("skill", """
                Call `gt.gt_tidy.neat("x")` first, then `gt.gt_tidy.nest("x")`.
                ```lua
                gt.gt_tidy.neat()
                ```
                """)));
        assertEquals(2, lint.size(), lint.toString());
        assertTrue(lint.stream().anyMatch(l -> l.problem().contains("there is no API function gt.gt_tidy.nest; did "
                + "you mean gt.gt_tidy.neat?")), lint.toString());
        assertTrue(lint.stream().anyMatch(l -> l.problem().contains("argument 'what' is missing")), lint.toString());
    }
}
