package com.dwinovo.numen.core.bench;

import com.dwinovo.numen.bench.Check;
import com.dwinovo.numen.bench.Scenario;
import com.dwinovo.numen.bench.Scene;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;

import java.util.ArrayList;
import java.util.List;

/**
 * 保护主人:夜里、简单难度,主人站在场地当中(僵尸围着他打,不会挤进场地的角落),三只僵尸在他身边五六格外生成;她站在
 * 场地另一头(离僵尸十几格,她自己的防御本能只管逼到她身边四格以内的怪,不会替她出手),包里一把铁剑。主人是生存模式、
 * 穿一身钻石甲,挨得住一阵。主人只说"保护我"。成功 = 三只僵尸全死;负面 = 她和主人都活着。
 *
 * <p>世界在每次运行开场拨回和平正午,这里改的夜里与难度只管这一次。
 */
public final class GuardOwner implements Scenario {

    private static final List<BlockPos> ZOMBIES = List.of(
            new BlockPos(14, 1, 6), new BlockPos(14, 1, 9), new BlockPos(12, 1, 12));

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
        owner.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.DIAMOND_HELMET));
        owner.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.DIAMOND_CHESTPLATE));
        owner.setItemSlot(EquipmentSlot.LEGS, new ItemStack(Items.DIAMOND_LEGGINGS));
        owner.setItemSlot(EquipmentSlot.FEET, new ItemStack(Items.DIAMOND_BOOTS));
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

    private long alive() {
        return zombies.stream().filter(Zombie::isAlive).count();
    }

    @Override
    public List<String> solution(Scene scene) {
        StringBuilder ids = new StringBuilder();
        zombies.forEach(z -> ids.append(' ').append(z.getId()));
        return List.of("fight attack --entity_ids" + ids);
    }
}
