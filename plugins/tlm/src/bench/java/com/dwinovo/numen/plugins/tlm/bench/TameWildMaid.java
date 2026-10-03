package com.dwinovo.numen.plugins.tlm.bench;

import com.dwinovo.numen.bench.Check;
import com.dwinovo.numen.bench.Scenario;
import com.dwinovo.numen.bench.Scene;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.List;

/**
 * 驯服一只野生女仆:场地里有一只没有主人的女仆,她包里一块蛋糕。主人只说要她把那只女仆收了。成功 = 女仆的主人是她;
 * 负面 = 女仆还活着(不能为了"收"而伤到她)。
 */
public final class TameWildMaid implements Scenario {

    /** 这一次生成的那只女仆。 */
    private EntityMaid maid;

    @Override
    public String id() {
        return "tame_wild_maid";
    }

    @Override
    public BlockPos start() {
        return new BlockPos(4, 1, 4);
    }

    @Override
    public void setup(Scene scene) {
        BlockPos at = scene.pos(10, 1, 10);
        maid = EntityMaid.TYPE.create(scene.level());
        maid.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0, 0);
        scene.level().addFreshEntity(maid);
        scene.give(new ItemStack(Items.CAKE));
    }

    @Override
    public String opening() {
        return "那边有只野生女仆,你去把她驯服了。";
    }

    @Override
    public List<Check> checks() {
        return List.of(
                Check.success("女仆归她", s -> s.assertTrue(maid.isOwnedBy(s.her()),
                        "女仆的主人是 " + maid.getOwnerUUID())),
                Check.guard("女仆还活着", s -> s.assertTrue(maid.isAlive(), "女仆没了")),
                Check.subgoal("蛋糕用掉了", s -> s.assertTrue(s.her().getInventory().countItem(Items.CAKE) == 0,
                        "蛋糕还在包里")));
    }

    @Override
    public String solution(Scene scene) {
        // use.entity 不走动:先走到她两格内
        BlockPos at = maid.blockPosition();
        return "move.goto_({" + at.getX() + ", " + at.getY() + ", " + at.getZ() + "}, {arrive = \"near\", near = 2})\n"
                + "use.entity(" + maid.getId() + ", {item = \"minecraft:cake\"})";
    }
}
