package com.dwinovo.numen.core.gametest;

import com.dwinovo.numen.core.Constants;
import com.dwinovo.numen.entity.CompanionFactory;
import com.dwinovo.numen.entity.NumenPlayer;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.BeforeBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Difficulty;
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

    /** 生存反射批次前置:和平难度 + 正午。 */
    @BeforeBatch(batch = "numen_survival")
    public static void prepareSurvivalBatch(ServerLevel level) {
        settleWorld(level, Difficulty.PEACEFUL, NOON);
    }

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

    /**
     * 换了一次维度之后,身体要<b>认下这次传送</b>。
     *
     * <p>换维度时服务端发一个带编号的传送包等客户端报数,而清"正在换维度"这个标记的<b>唯一</b>
     * 一处就是收到回执时({@code ServerGamePacketListenerImpl.handleAcceptTeleportPacket}),
     * 没有超时兜底。她没有客户端,回执不来,标记就恒为真,而它卡着两件事——
     * {@code ServerPlayer.processPortalCooldown} 只在标记为假时才递减冷却(过一次传送门
     * 冷却卡在 10,她再也进不去第二次),{@code ServerPlayer.isInvulnerableTo} 在标记为真时
     * 恒真(她从此无敌)。回执由 {@code FakeClient} 代答。
     */
    @GameTest(template = "floor16", timeoutTicks = 600, batch = "numen_survival")
    public static void a_dimension_change_is_acknowledged(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ServerLevel nether = level.getServer().getLevel(net.minecraft.world.level.Level.NETHER);
        helper.assertTrue(nether != null, "this server has no nether to travel to");
        // 落脚那片区块得真的在跑实体刻,否则她到了下界一刻都不 tick,冷却也就无从递减。
        // 真实游戏里这是主人在线时她自己那块加载垫干的事(见 CompanionChunkLoader);
        // GameTest 里没有在线的主人,所以这里直接把目的地钉住。
        nether.setChunkForced(0, 0, true);
        NumenPlayer companion = plainCompanion(helper, new BlockPos(6, 2, 6));
        companion.setPortalCooldown();
        int cooldown = companion.getPortalCooldown();
        helper.assertTrue(cooldown > 0, "portal cooldown did not start");

        helper.startSequence()
                .thenExecute(() -> companion.changeDimension(
                        new net.minecraft.world.level.portal.DimensionTransition(
                                nether, new Vec3(0.5, 70.0, 0.5), Vec3.ZERO, 0.0f, 0.0f,
                                net.minecraft.world.level.portal.DimensionTransition.DO_NOTHING)))
                .thenIdle(25)
                .thenWaitUntil(() -> {
                    helper.assertTrue(!companion.isChangingDimension(),
                            "she is still 'changing dimension' long after arriving — the portal cooldown "
                                    + "will never tick down and she cannot be hurt");
                    helper.assertTrue(companion.getPortalCooldown() < cooldown,
                            "the portal cooldown is stuck at " + companion.getPortalCooldown()
                                    + "; she can never use a portal again");
                    helper.assertTrue(!companion.isInvulnerableTo(level.damageSources().generic()),
                            "she is still invulnerable after the dimension change");
                })
                .thenExecute(() -> {
                    CompanionFactory.despawn(level.getServer(), companion);
                    nether.setChunkForced(0, 0, false);
                })
                .thenSucceed();
    }
}
