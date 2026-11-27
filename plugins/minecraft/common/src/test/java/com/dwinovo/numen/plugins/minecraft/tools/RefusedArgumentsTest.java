package com.dwinovo.numen.plugins.minecraft.tools;

import com.dwinovo.numen.plugins.minecraft.NumenApiFixture;
import com.dwinovo.numen.api.sdk.ApiTester;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 写不通的调用在碰到身体之前就退回:从她的入口跑(没有身体、没有世界),失败的那一次调用是参数错误,说清哪里不对、附上用法或
 * 改写好的那一行;身体的活一件都没派出去(派出去的话,这里没有身体,程序会停在等它上)。
 */
@Tag("mc")
class RefusedArgumentsTest {

    @BeforeAll
    static void install() {
        NumenApiFixture.install();
    }

    private static ApiTester.Run run(String code) {
        return ApiTester.run(null, UUID.randomUUID(), code);
    }

    private static JsonObject error(ApiTester.Run run) {
        assertFalse(run.ok(), run.message());
        return run.lastReply().getAsJsonObject("error");
    }

    /** armor 只是卸下的别名(四件甲),穿戴没有这个目标:按参数错误退回,下一行改写成不带槽位的那一次。 */
    @Test
    void wearingIntoTheArmorAliasIsRefusedAsOnlyForRemove() {
        JsonObject error = error(run("minecraft.gear.wear(\"minecraft:iron_helmet\", {slot = \"armor\"})"));
        assertEquals("bad_argument", error.get("kind").getAsString());
        assertTrue(error.get("message").getAsString().contains("is only for minecraft.gear.remove"), error.toString());
        assertEquals("minecraft.gear.wear(\"minecraft:iron_helmet\")", error.get("hint").getAsString());
    }

    /** 不说穿什么:这一行读不通,当场失败并附上 minecraft.gear.wear 的用法,一次调用也没派出去。 */
    @Test
    void wearingNothingFailsAtItsLineWithTheUsage() {
        ApiTester.Run run = run("minecraft.gear.wear()");
        assertFalse(run.ok());
        assertTrue(run.replies().isEmpty(), "a call that does not read was sent: " + run.replies());
        assertTrue(run.message().contains("usage: minecraft.gear.wear(item, {slot=…})"), run.message());
    }

    /** 床只给了两个坐标:一格是三个数,读不通,不猜是哪张床——参数错误,说 at 要一个 Pos。 */
    @Test
    void sleepingAtTwoCoordinatesAsksForAllThree() {
        ApiTester.Run run = run("minecraft.use.sleep({at = {x = 10, z = 20}})");
        assertFalse(run.ok());
        assertTrue(run.message().contains("bad_argument"), run.message());
        assertTrue(run.message().contains("argument 'at': expected a Pos {x = …, y = …, z = …}"), run.message());
    }

    /** 附魔台只有三项:第四项不用走到台子跟前就按参数错误退回,下一行是看这张台给这件东西哪几项。 */
    @Test
    void enchantingWithAFourthOptionIsRefusedBeforeWalking() {
        JsonObject error = error(run("minecraft.enchant.apply({x = 1, y = 64, z = 2}, \"minecraft:diamond_sword\", 4)"));
        assertEquals("bad_argument", error.get("kind").getAsString());
        assertTrue(error.get("message").getAsString().contains("options 1 to 3"), error.toString());
        assertEquals("minecraft.enchant.options({x = 1, y = 64, z = 2}, \"minecraft:diamond_sword\")",
                error.get("hint").getAsString());
    }

    /** 合成器的格只有 0 到 8:第 9 格按参数错误退回。 */
    @Test
    void aCrafterSlotOutsideItsGridIsRefused() {
        JsonObject error = error(run("minecraft.crafter.toggle({x = 1, y = 64, z = 2}, 9, false)"));
        assertEquals("bad_argument", error.get("kind").getAsString());
        assertTrue(error.get("message").getAsString().contains("slots are 0 to 8"), error.toString());
    }

    /** 信标没有"飞行"这个效果:按参数错误退回,说主效果有哪些。 */
    @Test
    void aBeaconEffectThatIsNoBeaconEffectIsRefusedWithTheOnesThereAre() {
        JsonObject error = error(run("minecraft.beacon.set({x = 1, y = 64, z = 2}, \"minecraft:iron_ingot\", \"flight\")"));
        assertEquals("bad_argument", error.get("kind").getAsString());
        assertTrue(error.get("message").getAsString().contains("speed, haste, resistance, jump_boost, strength"),
                error.toString());
    }
}
