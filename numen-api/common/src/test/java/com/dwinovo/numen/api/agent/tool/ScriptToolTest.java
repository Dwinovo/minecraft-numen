package com.dwinovo.numen.api.agent.tool;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 跑程序的工具的参数:程序与它做什么的那一句都要写,多写别的、写空、写成别的类型当场驳回并说清哪里不对。 */
class ScriptToolTest {

    private static String rejected(String arguments) {
        return assertThrows(IllegalArgumentException.class, () -> ScriptTool.parse(arguments)).getMessage();
    }

    @Test
    void takesTheProgramAndWhatItDoes() {
        ScriptTool.Program program = ScriptTool.parse(ScriptTool.args("return 1", "Return one").toString());
        assertEquals(new ScriptTool.Program("return 1", "Return one"), program);
    }

    @Test
    void bothArgumentsAreRequiredInTheSchema() {
        JsonObject schema = JsonParser.parseString(
                new com.google.gson.Gson().toJson(new ScriptTool().parameterSchema())).getAsJsonObject();
        assertEquals("[\"code\",\"description\"]", schema.getAsJsonArray("required").toString());
        assertEquals("[\"code\",\"description\"]", schema.getAsJsonObject("properties").keySet().stream()
                .map(k -> "\"" + k + "\"").collect(java.util.stream.Collectors.joining(",", "[", "]")));
    }

    @Test
    void aMissingDescriptionIsRejected() {
        assertTrue(rejected("{\"code\":\"return 1\"}").contains("'description' is missing"));
    }

    @Test
    void aBlankDescriptionIsRejected() {
        assertTrue(rejected("{\"code\":\"return 1\",\"description\":\"  \"}").contains("'description' is missing"));
    }

    @Test
    void aMissingOrBlankProgramIsRejected() {
        assertTrue(rejected("{\"description\":\"Return one\"}").contains("'code' is missing"));
        assertTrue(rejected("{\"code\":\" \",\"description\":\"Return one\"}").contains("'code' is missing"));
    }

    @Test
    void aNonStringArgumentIsRejected() {
        assertTrue(rejected("{\"code\":\"return 1\",\"description\":7}").contains("'description' is missing"));
        assertTrue(rejected("{\"code\":[\"return 1\"],\"description\":\"Return one\"}").contains("'code' is missing"));
    }

    @Test
    void anUnknownArgumentIsRejectedByName() {
        String why = rejected("{\"code\":\"return 1\",\"description\":\"Return one\",\"timeout\":5}");
        assertTrue(why.contains("unknown argument 'timeout'") && why.contains("code, description"), why);
    }

    @Test
    void argumentsThatAreNotJsonAreRejected() {
        assertTrue(rejected("return 1").startsWith("invalid arguments JSON"));
    }
}
