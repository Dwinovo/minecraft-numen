package com.dwinovo.numen.plugins.tlm.bench;

import com.dwinovo.numen.bench.Check;
import com.dwinovo.numen.bench.Scenario;
import com.dwinovo.numen.bench.Scene;
import com.dwinovo.numen.sdk.Positions;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FarmBlock;
import net.neoforged.neoforge.items.IItemHandler;

import java.util.List;

/**
 * 收一只野生女仆当农夫:场地里一只没有主人的女仆,一块三乘三的湿耕地(旁边一格水),她包里一块蛋糕和 16 颗小麦种子。
 * 主人说把那只女仆收了,让她帮着种这块地。要成事得驯服她(蛋糕)、把种子放进她的格子(种地要她自己有种子)、把她的工作切成种地、
 * 开家模式让她守在田边。成功 = 女仆归她、女仆当前工作是种地、女仆格子里有种子;子目标 = 驯服、交种子、切模式、家模式开着且家在耕地附近;
 * 负面 = 女仆还活着。
 */
public final class MaidFarmhand implements Scenario {

    private static final ResourceLocation FARM = ResourceLocation.fromNamespaceAndPath("touhou_little_maid", "farm");
    private static final int SEEDS = 16;
    /** 耕地:三乘三,y=0 的一层;水在它西边一格,耕地都在它的水润范围里。 */
    private static final BlockPos FIELD_LO = new BlockPos(10, 0, 10);
    private static final BlockPos FIELD_HI = new BlockPos(12, 0, 12);
    private static final BlockPos WATER = new BlockPos(9, 0, 11);
    /** 家离耕地中心多远以内算在田边。 */
    private static final double HOME_RANGE = 8;

    /** 这一次生成的那只女仆。 */
    private EntityMaid maid;

    @Override
    public String id() {
        return "maid_farmhand";
    }

    @Override
    public BlockPos start() {
        return new BlockPos(4, 1, 4);
    }

    @Override
    public void setup(Scene scene) {
        for (int x = FIELD_LO.getX(); x <= FIELD_HI.getX(); x++) {
            for (int z = FIELD_LO.getZ(); z <= FIELD_HI.getZ(); z++) {
                scene.set(x, 0, z, Blocks.FARMLAND.defaultBlockState().setValue(FarmBlock.MOISTURE, 7));
            }
        }
        scene.set(WATER.getX(), WATER.getY(), WATER.getZ(), Blocks.WATER.defaultBlockState());
        BlockPos at = scene.pos(7, 1, 7);
        maid = EntityMaid.TYPE.create(scene.level());
        maid.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0, 0);
        scene.level().addFreshEntity(maid);
        scene.give(new ItemStack(Items.CAKE));
        scene.give(new ItemStack(Items.WHEAT_SEEDS, SEEDS));
    }

    @Override
    public String opening() {
        return "把那只女仆收了,让她帮我种这块地。";
    }

    @Override
    public List<Check> checks() {
        return List.of(
                Check.success("女仆归她", s -> s.assertTrue(maid.isOwnedBy(s.her()), ownerWords())),
                Check.success("女仆当前工作是种地", s -> s.assertTrue(farming(), "她的工作是 " + maid.getTask().getUid())),
                Check.success("女仆格子里有种子", s -> s.assertTrue(seeds() > 0, "女仆格子里没有种子")),
                Check.guard("女仆还活着", s -> s.assertTrue(maid.isAlive(), "女仆没了")),
                Check.subgoal("驯服", s -> s.assertTrue(maid.isOwnedBy(s.her()), ownerWords())),
                Check.subgoal("交种子", s -> s.assertTrue(seeds() > 0 && s.her().getInventory().countItem(Items.WHEAT_SEEDS) < SEEDS,
                        "她包里还有 " + s.her().getInventory().countItem(Items.WHEAT_SEEDS) + " 颗,女仆格子里 " + seeds() + " 颗")),
                Check.subgoal("切到种地", s -> s.assertTrue(farming(), "她的工作是 " + maid.getTask().getUid())),
                Check.subgoal("家模式开着、家在耕地附近", s -> s.assertTrue(
                        maid.isHomeModeEnable() && homeDistance(s) <= HOME_RANGE,
                        "家模式 " + maid.isHomeModeEnable() + ",家离耕地中心 " + String.format("%.1f", homeDistance(s)) + " 格")));
    }

    private String ownerWords() {
        return "女仆的主人是 " + maid.getOwnerUUID();
    }

    private boolean farming() {
        return maid.getTask().getUid().equals(FARM);
    }

    /** 女仆各个格子(手、装备、背包)里的小麦种子。 */
    private int seeds() {
        IItemHandler inv = maid.getAvailableInv(true);
        int n = 0;
        for (int i = 0; i < inv.getSlots(); i++) {
            ItemStack stack = inv.getStackInSlot(i);
            n += stack.is(Items.WHEAT_SEEDS) ? stack.getCount() : 0;
        }
        return n;
    }

    /** 她的家(限制中心)到耕地中心的水平距离。 */
    private double homeDistance(Scene scene) {
        BlockPos home = maid.getRestrictCenter();
        BlockPos middle = scene.pos(11, 0, 11);
        return Math.hypot(home.getX() - middle.getX(), home.getZ() - middle.getZ());
    }

    @Override
    public String solution(Scene scene) {
        // numen.use.entity 不走动:先走到她两格内;驯服之后开她的界面,把种子放进去,再切工作、开家模式(家就是她站的地方)
        int id = maid.getId();
        return "numen.move.to(" + Positions.literal(maid.blockPosition()) + ", {arrive = \"near\", range = 2})\n"
                + "numen.use.entity(" + id + ", {item = \"minecraft:cake\"})\n"
                + "tlm.maid.open(" + id + ")\n"
                + "numen.gui.put(\"minecraft:wheat_seeds\")\n"
                + "numen.gui.close()\n"
                + "tlm.maid.task(\"touhou_little_maid:farm\", {maid = " + id + "})\n"
                + "tlm.maid.config(" + id + ", {home = true})";
    }
}
