package com.dwinovo.numen.plugins.tlm.bench;

import com.dwinovo.numen.bench.Check;
import com.dwinovo.numen.bench.Scenario;
import com.dwinovo.numen.bench.Scene;
import com.dwinovo.numen.sdk.Positions;
import com.github.tartaricacid.touhoulittlemaid.block.multiblock.MultiBlockAltar;
import com.github.tartaricacid.touhoulittlemaid.data.PowerAttachment;
import com.github.tartaricacid.touhoulittlemaid.entity.item.EntityTombstone;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.init.InitDataAttachment;
import com.github.tartaricacid.touhoulittlemaid.tileentity.TileEntityAltar;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.List;

/**
 * 把女仆救回来:她自己的一只女仆死了(留下墓碑,墓碑里有她的东西和她的胶片),一座祭坛已经用代码搭好(车万女仆自己的
 * 多方块,和玩家用博丽御币搭出来的一样),她包里有祭坛复活要的材料(青金石、金锭、红石、铁锭、煤,车万女仆 1.5.3 的
 * {@code altar_recipe/reborn_maid}),P 点是满的。主人说把女仆救回来,别用神社(神社会把她的血直接设成 0.25)。
 * 要成事得认出墓碑、取回胶片,再把六样东西逐样放到祭坛的六根柱子上——放齐那一下就复活。
 * 成功 = 场地里有一只活着、归她的女仆,墓碑没了;负面 = 她没死(每个场景都有)。
 */
public final class ReviveMaid implements Scenario {

    private static final ResourceLocation ALTAR_SOUTH =
            ResourceLocation.fromNamespaceAndPath("touhou_little_maid", "altar_south");
    private static final ResourceLocation FILM = ResourceLocation.fromNamespaceAndPath("touhou_little_maid", "film");
    /** 祭坛的起点(模板的原点)。 */
    private static final BlockPos ALTAR = new BlockPos(5, 1, 5);
    /** 女仆死在哪。 */
    private static final BlockPos DEATH = new BlockPos(16, 1, 16);
    /** 复活要的五样,胶片在墓碑里。 */
    private static final List<Item> MATERIALS = List.of(Items.LAPIS_LAZULI, Items.GOLD_INGOT, Items.REDSTONE,
            Items.IRON_INGOT, Items.COAL);

    /** 她名下将要死的那只女仆,与祭坛上六个能放东西的格子(世界坐标),搭场景时记下,给标准解用。 */
    private EntityMaid maid;
    private final List<BlockPos> pillars = new ArrayList<>();

    @Override
    public String id() {
        return "revive_maid";
    }

    @Override
    public void setup(Scene scene) {
        ServerLevel level = scene.level();
        BlockPos start = scene.pos(ALTAR);
        StructureTemplate template = level.getStructureManager().getOrCreate(ALTAR_SOUTH);
        template.placeInWorld(level, start, start, new StructurePlaceSettings(), level.getRandom(), 2);
        new MultiBlockAltar().build(level, start, Direction.SOUTH, template);
        // 能放东西的柱顶,以车万女仆记在祭坛方块上的那一份为准
        pillars.addAll(((TileEntityAltar) level.getBlockEntity(start.offset(0, 2, 2)))
                .getCanPlaceItemPosList().getData());

        BlockPos at = scene.pos(DEATH);
        maid = EntityMaid.TYPE.create(level);
        maid.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0, 0);
        level.addFreshEntity(maid);
        maid.tame(scene.her());

        scene.her().setData(InitDataAttachment.POWER_NUM, new PowerAttachment(PowerAttachment.MAX_POWER));
        MATERIALS.forEach(item -> scene.give(new ItemStack(item)));
        // 死在搭场景时:maid_died 急件先到、先叫醒她,她那一轮跑完主人才开口(见 Attempt.tick)
        maid.kill();
    }

    @Override
    public String opening() {
        return "把女仆救回来,别用神社。";
    }

    private static AABB field(Scene scene) {
        return new AABB(scene.pos(0, 0, 0)).minmax(new AABB(scene.pos(20, 12, 20)));
    }

    private static List<EntityMaid> living(Scene scene) {
        return scene.level().getEntitiesOfClass(EntityMaid.class, field(scene),
                m -> m.isAlive() && m.isOwnedBy(scene.her()));
    }

    private static List<EntityTombstone> tombstones(Scene scene) {
        return scene.level().getEntitiesOfClass(EntityTombstone.class, field(scene));
    }

    @Override
    public List<Check> checks() {
        return List.of(
                Check.success("场地里有一只活着、归她的女仆", s -> s.assertTrue(!living(s).isEmpty(),
                        "没有活着的、归她的女仆")),
                Check.success("墓碑没了", s -> s.assertTrue(tombstones(s).isEmpty(),
                        "场地里还有 " + tombstones(s).size() + " 块墓碑")),
                Check.subgoal("材料放上了祭坛", s -> s.assertTrue(
                        MATERIALS.stream().noneMatch(item -> s.her().getInventory().countItem(item) > 0),
                        "她包里还有祭坛要的材料")));
    }

    @Override
    public String solution(Scene scene) {
        // 取墓碑里的东西(胶片)要走到它旁边;祭坛的柱子逐根走到手边、拿着一样右键一下,六样放齐就复活
        StringBuilder program = new StringBuilder("""
                local tombstone
                for _, e in ipairs(numen.scan.entities("all", {radius = 32})) do
                  if e.type == "touhou_little_maid:tombstone" then
                    tombstone = e
                    break
                  end
                end
                numen.move.to(tombstone, {arrive = "near", range = 2})
                numen.use.entity(tombstone)
                """);
        List<String> items = new ArrayList<>(List.of(FILM.toString()));
        MATERIALS.forEach(item -> items.add(BuiltInRegistries.ITEM.getKey(item).toString()));
        for (int i = 0; i < pillars.size(); i++) {
            String pillar = Positions.literal(pillars.get(i));
            program.append("numen.move.to(").append(pillar).append(", {arrive = \"use\"})\n");
            program.append("numen.gear.hold(\"").append(items.get(i)).append("\")\n");
            program.append("numen.use.block(").append(pillar).append(")\n");
        }
        return program.toString();
    }
}
