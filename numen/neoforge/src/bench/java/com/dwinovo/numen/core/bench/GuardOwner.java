package com.dwinovo.numen.core.bench;

import com.dwinovo.numen.bench.Check;
import com.dwinovo.numen.bench.Scenario;
import com.dwinovo.numen.bench.Scene;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 保护主人:夜里、简单难度,主人站在场地当中(僵尸围着他打,不会挤进场地的角落),三只僵尸在他身边五六格外生成;她站在
 * 场地另一头(离僵尸十几格,她自己的防御本能只管逼到她身边四格以内的怪,不会替她出手),包里一把铁剑。主人是生存模式、
 * {@link #OWNER_HEALTH} 点血:模拟主人不走自己的那一刻(连接不驱动它),身上的甲算不进护甲值,20 点血在三只僵尸手里撑不过
 * 十几秒,连她听完一句话、走过去都不够;加厚到三倍,一开口就动身的话赶得上。主人只说"保护我"。成功 = 三只僵尸全死;
 * 负面 = 她和主人都活着。收场时主人剩的血量记进结果(指标 {@code owner_health},不作判据)。
 *
 * <p>世界在每次运行开场拨回和平正午,这里改的夜里与难度只管这一次。
 */
public final class GuardOwner implements Scenario {

    private static final List<BlockPos> ZOMBIES = List.of(
            new BlockPos(14, 1, 6), new BlockPos(14, 1, 9), new BlockPos(12, 1, 12));

    private static final float OWNER_HEALTH = 60;

    private final List<Zombie> zombies = new ArrayList<>();

    @Override
    public String id() {
        return "guard_owner";
    }

    @Override
    public BlockPos start() {
        return new BlockPos(2, 1, 18);
    }

    @Override
    public BlockPos ownerAt() {
        return new BlockPos(8, 1, 8);
    }

    @Override
    public void setup(Scene scene) {
        scene.level().getServer().setDifficulty(Difficulty.EASY, true);
        scene.level().setDayTime(18000);
        ServerPlayer owner = scene.owner();
        owner.setGameMode(GameType.SURVIVAL);
        owner.getAttribute(Attributes.MAX_HEALTH).setBaseValue(OWNER_HEALTH);
        owner.setHealth(OWNER_HEALTH);
        for (BlockPos rel : ZOMBIES) {
            BlockPos at = scene.pos(rel);
            Zombie zombie = EntityType.ZOMBIE.create(scene.level());
            zombie.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0, 0);
            zombie.setPersistenceRequired();
            scene.level().addFreshEntity(zombie);
            zombies.add(zombie);
        }
        scene.give(new ItemStack(Items.IRON_SWORD));
    }

    @Override
    public String opening() {
        return "保护我!";
    }

    @Override
    public List<Check> checks() {
        return List.of(
                Check.success("僵尸全死了", s -> s.assertTrue(alive() == 0, "还有 " + alive() + " 只僵尸活着")),
                Check.guard("主人活着", s -> s.assertTrue(s.owner().isAlive(), "主人死了")),
                Check.subgoal("打死了至少一只", s -> s.assertTrue(alive() < ZOMBIES.size(), "一只都没打死")));
    }

    @Override
    public Map<String, Double> metrics(Scene scene) {
        return Map.of("owner_health", (double) scene.owner().getHealth());
    }

    private long alive() {
        return zombies.stream().filter(Zombie::isAlive).count();
    }

    @Override
    public String solution(Scene scene) {
        StringBuilder program = new StringBuilder();
        zombies.forEach(z -> program.append("numen.fight.attack(").append(z.getId()).append(")\n"));
        return program.toString();
    }
}
