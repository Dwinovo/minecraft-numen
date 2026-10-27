package com.dwinovo.numen.tools;

import com.dwinovo.numen.NumenApiFixture;
import com.dwinovo.numen.api.sdk.ApiTester;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** 形状测试的共用件:从她的入口跑一段 Lua(没有世界),把交回的一串格读成 Java 的格。 */
final class ShapeLua {

    private ShapeLua() {}

    /** 一格:位置与方块(只有位置时 name 为 null)。 */
    record Cell(int x, int y, int z, String name) {

        String at() {
            return x + "," + y + "," + z;
        }
    }

    /** 程序 {@code return} 的值。 */
    static JsonElement value(String expression) {
        NumenApiFixture.install();
        ApiTester.Run run = ApiTester.run(null, UUID.randomUUID(), "return " + expression);
        assertTrue(run.ok(), run.message());
        return run.returned();
    }

    /** 程序跑到的结局文字:出错时错误话在里面。 */
    static ApiTester.Run run(String code) {
        NumenApiFixture.install();
        return ApiTester.run(null, UUID.randomUUID(), code);
    }

    /** 一个表达式交回的一串格,按交回的先后。 */
    static List<Cell> cells(String expression) {
        List<Cell> out = new ArrayList<>();
        for (JsonElement item : value(expression).getAsJsonArray()) {
            JsonObject o = item.getAsJsonObject();
            JsonObject pos = o.has("pos") ? o.getAsJsonObject("pos") : o;
            out.add(new Cell(pos.get("x").getAsInt(), pos.get("y").getAsInt(), pos.get("z").getAsInt(),
                    o.has("name") ? o.get("name").getAsString() : null));
        }
        return out;
    }

    /** 一串格的位置,{@code "x,y,z"} 的集合。 */
    static Set<String> positions(String expression) {
        Set<String> out = new LinkedHashSet<>();
        cells(expression).forEach(c -> out.add(c.at()));
        return out;
    }

    static String at(int x, int y, int z) {
        return x + "," + y + "," + z;
    }

    /** 一个位置的 Lua 写法。 */
    static String pos(int x, int y, int z) {
        return "{x = " + x + ", y = " + y + ", z = " + z + "}";
    }

    static JsonArray array(String expression) {
        return value(expression).getAsJsonArray();
    }
}
