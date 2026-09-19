package com.dwinovo.numen.core.gametest;

import com.dwinovo.numen.core.Constants;
import com.dwinovo.numen.entity.CompanionFactory;
import com.dwinovo.numen.entity.NumenPlayer;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import static com.dwinovo.numen.core.gametest.GameTestKit.*;

/** 身体的生存反射:摔落伤害、落地水桶、本能告诉她是哪一条出的手。 */
@GameTestHolder(Constants.MOD_ID)
@PrefixGameTestTemplate(false)
public class SurvivalGameTests {

    /**
     * 出生无敌的刻数。原版 {@code ServerPlayer.spawnInvulnerableTime = 60} 会把这段时间里
     * 的一切伤害挡掉(摔落只在<b>专用服</b>且开着 PVP 时才豁免,gametest 两条都不占),
     * 所以摔落用例必须等它走完再把人提上去 —— 不等的话看到的是"补丁没生效"。
     */
    private static final int SPAWN_INVULNERABLE_TICKS = 70;

    /** 起跳高度(相对模板);模板只有 6 格高,落差得从模板上方取。 */
    private static final int DROP_HEIGHT = 18;

    /**
     * 摔落<b>真的会掉血</b>。
     *
     * <p>玩家的摔落结算在原版里是客户端权威的:{@code ServerPlayer.checkFallDamage} 是个
     * 空实现,真正结算的是收到移动包时的 {@code doCheckFallDamage}。空壳玩家的连接是空的,
     * 那个包永远不来 —— 她从任意高度跳下去毫发无伤,而 {@code fallDistance} 恒为 0,
     * 靠它触发的东西一律是死的。这条守 {@code NumenPlayer.tick()} 里补回来的那一趟。
     */
    @GameTest(template = "floor16", timeoutTicks = 100000, batch = "numen_survival")
    public static void fall_damage_reaches_the_body(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        NumenPlayer companion = plainCompanion(helper, new BlockPos(4, 2, 4));
        float full = companion.getMaxHealth();
        helper.startSequence()
                .thenExecuteAfter(SPAWN_INVULNERABLE_TICKS,
                        () -> drop(helper, companion, new BlockPos(4, DROP_HEIGHT, 4)))
                .thenWaitUntil(() -> {
                    helper.assertTrue(companion.onGround(), "companion is still in the air");
                    helper.assertTrue(companion.getHealth() < full,
                            "the fall did no damage (health " + companion.getHealth() + ")");
                })
                .thenExecute(() -> CompanionFactory.despawn(level.getServer(), companion))
                .thenSucceed();
    }

    /**
     * 摔落自救:铺水接住自己,<b>而且把水收回来</b>。
     *
     * <p>收水是这条的重点 —— 桶是消耗品,放完不收就只能救一次,第二次直接摔死。
     */
    @GameTest(template = "floor16", timeoutTicks = 100000, batch = "numen_survival")
    public static void mlg_breaks_the_fall_and_takes_the_water_back(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        NumenPlayer companion = plainCompanion(helper, new BlockPos(11, 2, 11));
        companion.getInventory().add(new ItemStack(Items.WATER_BUCKET));
        float full = companion.getMaxHealth();
        helper.startSequence()
                .thenExecuteAfter(SPAWN_INVULNERABLE_TICKS,
                        () -> drop(helper, companion, new BlockPos(11, DROP_HEIGHT, 11)))
                .thenWaitUntil(() -> {
                    helper.assertTrue(companion.onGround(), "companion is still in the air");
                    helper.assertTrue(companion.getHealth() == full,
                            "the water bucket did not break the fall (health "
                                    + companion.getHealth() + ")");
                    helper.assertTrue(carries(companion, Items.WATER_BUCKET),
                            "the water was placed but never picked back up");
                })
                .thenExecute(() -> CompanionFactory.despawn(level.getServer(), companion))
                .thenSucceed();
    }

    /**
     * 本能替身体做了事,她要听到的是一条 {@code reflex} 事件,带着是哪个本能:摔落时铺水接住自己,
     * 主人不在线,这件事以 {@code reflex} 类型进出箱,文本里写着本能名册里的登记名 {@code mlg}。
     */
    @GameTest(template = "floor16", timeoutTicks = 100000, batch = "numen_survival")
    public static void a_reflex_tells_her_which_instinct_acted(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        NumenPlayer companion = plainCompanion(helper, new BlockPos(11, 2, 11));
        companion.getInventory().add(new ItemStack(Items.WATER_BUCKET));
        var outbox = com.dwinovo.numen.entity.EventOutbox.get(level.getServer());
        helper.startSequence()
                .thenExecuteAfter(SPAWN_INVULNERABLE_TICKS,
                        () -> drop(helper, companion, new BlockPos(11, DROP_HEIGHT, 11)))
                .thenWaitUntil(() -> {
                    var reflexes = outbox.peek(companion.getUUID()).entries().stream()
                            .filter(e -> e.type().equals(com.dwinovo.numen.agent.inbox.EventTypes.REFLEX))
                            .toList();
                    helper.assertTrue(!reflexes.isEmpty(), "no reflex event was kept for the offline owner: "
                            + outbox.peek(companion.getUUID()).entries());
                    helper.assertTrue(reflexes.get(0).text().startsWith("<event kind=\"reflex\"")
                                    && reflexes.get(0).text().contains("reflex=\"mlg\""),
                            "the reflex event does not name its instinct: " + reflexes.get(0).text());
                })
                .thenExecute(() -> {
                    outbox.forget(companion.getUUID());
                    CompanionFactory.despawn(level.getServer(), companion);
                })
                .thenSucceed();
    }

    /** 空背包的同伴,落在 rel 那一格。 */
    private static NumenPlayer plainCompanion(GameTestHelper helper, BlockPos rel) {
        ServerLevel level = helper.getLevel();
        BlockPos at = helper.absolutePos(rel);
        NumenPlayer companion = CompanionFactory.spawn(level.getServer(), UUID.randomUUID(),
                "gametest_faller", UUID.randomUUID(), level,
                new Vec3(at.getX() + 0.5, at.getY(), at.getZ() + 0.5));
        companion.getFoodData().setFoodLevel(20);
        return companion;
    }
}
