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
 * 第 0 层的一行指令说的话由她来源的回话去处({@link Echo})收成回执;主人点过头的调用,回执末尾交代那一句。
 * 真服务器上的回显、长活的受理与收尾对得上号,在 GameTest 里验。
 */
class InvocationTest {

    private static ServerSource call(List<String> replies) {
        return new ServerSource(null, CommandTool.NAME, "call-1", CommandTool.args("/give @s minecraft:diamond 2"),
                replies::add);
    }

    private static JsonObject only(List<String> replies) {
        assertEquals(1, replies.size(), "恰好一次回执: " + replies);
        return JsonParser.parseString(replies.get(0)).getAsJsonObject();
    }

    @Test
    void whatACommandSaysBecomesTheReceipt() {
        List<String> replies = new ArrayList<>();
        Echo echo = new Echo(false);
        echo.sendSystemMessage(Component.literal("Gave 1 [Diamond] to Aria"));
        echo.sendSystemMessage(Component.literal("Gave 1 [Diamond] to Aria"));
        echo.onResult(true, 1);
        echo.onResult(false, 0);
        echo.onResult(true, 1);
        call(replies).reply(echo.receipt("give @s minecraft:diamond 2", () -> "\nmore"));

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
        Echo echo = new Echo(false);
        call(replies).reply(echo.receipt("give @s minecraft:diamond 2", () -> {
            throw new AssertionError("没跑成的不去取接在后面的那一截");
        }));
        JsonObject receipt = only(replies);
        assertFalse(receipt.get("success").getAsBoolean(), "没有结果回调就是没跑成");
        assertEquals("/give @s minecraft:diamond 2 failed: (no output)", receipt.get("message").getAsString());
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
