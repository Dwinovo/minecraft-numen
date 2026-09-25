package com.dwinovo.numen.cli;

import com.dwinovo.numen.task.TaskResult;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 一次调用在服务端怎么跟着指令走:执行入口放进她来源的回话去处({@link Echo})带着这次调用,{@code /numen} 的节点
 * 取出来交给处理函数、由处理函数回执;别的指令说的话由它收成回执。主人点过头的调用,回执末尾交代那一句。
 * 从来源里把它取回来(要 mixin)、长活的受理与收尾对得上号,在 GameTest 里对着真服务器验。
 */
class InvocationTest {

    private static ServerSource call(List<String> replies) {
        return new ServerSource(null, CommandTool.NAME, "call-1", CommandTool.args("give @s minecraft:diamond 2"),
                replies::add);
    }

    private static JsonObject only(List<String> replies) {
        assertEquals(1, replies.size(), "恰好一次回执: " + replies);
        return JsonParser.parseString(replies.get(0)).getAsJsonObject();
    }

    @Test
    void whatACommandSaysBecomesTheReceipt() {
        List<String> replies = new ArrayList<>();
        Echo echo = new Echo(call(replies));
        echo.sendSystemMessage(Component.literal("Gave 1 [Diamond] to Aria"));
        echo.sendSystemMessage(Component.literal("Gave 1 [Diamond] to Aria"));
        echo.onResult(true, 1);
        echo.onResult(false, 0);
        echo.onResult(true, 1);
        echo.settle("give @s minecraft:diamond 2", () -> "\nmore");

        JsonObject receipt = only(replies);
        assertTrue(receipt.get("success").getAsBoolean(), "分叉的指令有一支成功就算成功");
        assertEquals("ran /give @s minecraft:diamond 2: Gave 1 [Diamond] to Aria\nGave 1 [Diamond] to Aria\nmore",
                receipt.get("message").getAsString(), "跑成了,接的那一截在原话之后");
        assertEquals(2, receipt.getAsJsonObject("data").get("result").getAsInt(), "各支返回的数相加");
        assertEquals("/give @s minecraft:diamond 2", receipt.getAsJsonObject("data").get("command").getAsString());
    }

    @Test
    void aCommandThatNeverRanFailsWithWhatItSaid() {
        List<String> replies = new ArrayList<>();
        Echo echo = new Echo(call(replies));
        echo.settle("give @s minecraft:diamond 2", () -> {
            throw new AssertionError("没跑成的不去取接在后面的那一截");
        });
        JsonObject receipt = only(replies);
        assertFalse(receipt.get("success").getAsBoolean(), "没有结果回调就是没跑成");
        assertEquals("/give @s minecraft:diamond 2 failed: (no output)", receipt.get("message").getAsString());
    }

    @Test
    void aNumenNodeAnswersTheCallItself() {
        List<String> replies = new ArrayList<>();
        Echo echo = new Echo(call(replies));
        echo.call().reply(TaskResult.ok("done by the handler").toJson());
        echo.answered();
        echo.sendSystemMessage(Component.literal("ignored"));
        echo.settle("numen task status", () -> "");
        assertEquals("done by the handler", only(replies).get("message").getAsString(),
                "处理函数答了,回显不再作回执");
    }

    @Test
    void anAllowedCallEndsItsReceiptWithTheOwnersAllowance() {
        List<String> replies = new ArrayList<>();
        ServerSource allowed = call(replies).allowed("the owner allowed: run /setblock 0 64 0 stone");
        allowed.reply(TaskResult.ok("ran /setblock 0 64 0 stone: Changed the block").toJson());
        assertEquals("ran /setblock 0 64 0 stone: Changed the block the owner allowed: run /setblock 0 64 0 stone.",
                only(replies).get("message").getAsString());
    }
}
