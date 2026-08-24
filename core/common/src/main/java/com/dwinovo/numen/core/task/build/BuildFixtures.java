package com.dwinovo.numen.core.task.build;

import com.dwinovo.numen.core.build.BlueprintSafety;
import com.dwinovo.numen.entity.NumenPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.AABB;

/**
 * 收工阶段的摆设与善后:生成图纸里的实体(展示框、盔甲架、画)、
 * 给工地外围的水重排流体 tick。付不起的摆设与身上带的东西按件记账
 * ({@link #skippedFixtures()}/{@link #skippedPayloads()}),不静默消失。
 */
final class BuildFixtures {

    private final NumenPlayer player;
    private final BuildTaskRecord r;
    private final BuildInventory inv;

    /** 收工时因缺料没能生成的摆设数——不记的话它们会静默消失而任务照报成功。 */
    private int skippedFixtures;
    /** 摆设生成了,但身上带的东西没付起——框空着挂上去了,同样得交代。 */
    private int skippedPayloads;

    BuildFixtures(NumenPlayer player, BuildTaskRecord r, BuildInventory inv) {
        this.player = player;
        this.r = r;
        this.inv = inv;
    }

    int skippedFixtures() {
        return skippedFixtures;
    }

    int skippedPayloads() {
        return skippedPayloads;
    }

    /**
     * 收工时把图纸里的摆设实体生成出来:展示框、盔甲架、画。
     *
     * <p>放在最后一步,因为它们要挂在墙上、立在地上——墙和地得先有。
     */
    void spawnAll() {
        if (r.entities.isEmpty() || !(player.level() instanceof ServerLevel level)) {
            return;
        }
        for (BuildTaskRecord.EntitySpawn spawn : r.entities) {
            try {
                if (alreadyThere(spawn)) {
                    continue;   // 重发同一个调用是续建,不该再生成一份
                }
                boolean pays = r.consumeMaterials && spawn.item() != Items.AIR;
                if (pays && !inv.hasItems(spawn.item(), 1, true)) {
                    // 这一步在收工里,passMissing 此后没人读——不记一笔的话
                    // 摆设静默消失而任务照报成功。
                    skippedFixtures++;
                    continue;
                }
                // 身上带的东西另算,而且按组件全等收:框里那把锋利五的剑,得他真有一把
                // 才装得上。收什么放什么——付不起就整个拿掉,框空着挂上去,如实记一笔。
                var carried = spawn.payload();
                boolean paidCarried = true;
                if (r.consumeMaterials) {
                    for (ItemStack want : carried) {
                        if (inv.strictCount(want) < 1) {
                            paidCarried = false;
                            break;
                        }
                    }
                }
                // 挂件的锚点必须在读档<b>之前</b>写好:它是绝对坐标,读档时就定了,
                // moveTo 改不到它(那只改视觉位置)。旋转过的图纸里挂件朝向也存在
                // NBT 里,一并按图纸转过去——直接调 HangingEntity.rotate() 会改朝向
                // 字段却不重算碰撞箱,两者当场脱钩。
                var nbt = spawn.nbt().copy();
                if (!paidCarried) {
                    BlueprintSafety.stripPayload(nbt);
                    skippedPayloads += carried.size();
                }
                boolean hangs = !"minecraft:armor_stand".equals(nbt.getString("id"));
                // 位置与锚点同源写入。读档时锚点要过一道 16 格闸门:锚点离 Pos 超过
                // 16 格就被判成坏档丢掉,而丢掉之后重算碰撞箱会拿一个 null 坐标去算
                // 中心点,当场 NPE、这只摆设静默消失。加载器把两个键都删了,所以这里
                // 一起补上,闸门看到的是同一个点。
                net.minecraft.nbt.ListTag at = new net.minecraft.nbt.ListTag();
                at.add(net.minecraft.nbt.DoubleTag.valueOf(spawn.x()));
                at.add(net.minecraft.nbt.DoubleTag.valueOf(spawn.y()));
                at.add(net.minecraft.nbt.DoubleTag.valueOf(spawn.z()));
                nbt.put("Pos", at);
                if (hangs) {
                    BlockPos anchor = BlockPos.containing(spawn.x(), spawn.y(), spawn.z());
                    nbt.putInt("TileX", anchor.getX());
                    nbt.putInt("TileY", anchor.getY());
                    nbt.putInt("TileZ", anchor.getZ());
                }
                // 朝向是两套键名两套编码,不是一套:画存小写 facing、按水平四向编码;
                // 展示框存大写 Facing、按六向编码(它能挂在天花板和地板上)。混用的
                // 后果是一半的墙面展示框转向错误,随后立不住掉落。
                if (nbt.contains("facing")) {
                    Direction facing = Direction.from2DDataValue(nbt.getByte("facing"));
                    nbt.putByte("facing", (byte) spawn.rotation().rotate(facing).get2DDataValue());
                }
                if (nbt.contains("Facing")) {
                    Direction facing = Direction.from3DDataValue(nbt.getByte("Facing"));
                    nbt.putByte("Facing", (byte) spawn.rotation().rotate(facing).get3DDataValue());
                }
                var created = net.minecraft.world.entity.EntityType.create(nbt, level);
                if (created.isEmpty()) {
                    continue;
                }
                var entity = created.get();
                // 挂件的朝向已经在 NBT 里转好了;盔甲架不是挂件,它的朝向只有偏航角,
                // 走基类那个纯函数版的 rotate(只返回旋转后的偏航角,不改任何字段)。
                float yaw = hangs ? entity.getYRot() : entity.rotate(spawn.rotation());
                entity.moveTo(spawn.x(), spawn.y(), spawn.z(), yaw, entity.getXRot());
                entity.setUUID(java.util.UUID.randomUUID());   // 同一张图纸建两遍不能撞 UUID
                level.addFreshEntity(entity);
                if (pays) {
                    inv.consumeOne(spawn.item());
                }
                if (paidCarried && r.consumeMaterials) {
                    for (ItemStack want : carried) {
                        inv.consumeStrict(want);
                    }
                }
            } catch (RuntimeException ignored) {
                // 一只摆设生成失败不该让整栋楼算失败
            }
        }
    }

    /**
     * 这只摆设已经在那儿了吗——<b>实体的幂等靠这一步</b>。
     *
     * <p>方块能安全重发,是因为我们逐格拿世界当进度对照;而"重发同一个调用就是续建"
     * 正是我们写进工具描述、教给模型的做法。实体没有这一步的话,建完再发一次就多出
     * 一份摆设——同一面墙上两个展示框叠在一起。
     */
    boolean alreadyThere(BuildTaskRecord.EntitySpawn spawn) {
        if (!(player.level() instanceof ServerLevel level)) {
            return false;
        }
        var type = net.minecraft.world.entity.EntityType.by(spawn.nbt());
        if (type.isEmpty()) {
            return false;
        }
        AABB box = new AABB(spawn.x() - 0.5, spawn.y() - 0.5, spawn.z() - 0.5,
                spawn.x() + 0.5, spawn.y() + 0.5, spawn.z() + 0.5);
        return !level.getEntities(type.get(), box, e -> true).isEmpty();
    }

    /**
     * 收工时给紧贴工地外围一圈的水重排一次流体 tick。
     *
     * <p>我们落位不带邻居更新——那是为了不让原版中途改写图纸,但代价是<b>周围的水
     * 不知道世界变了</b>:在湖里砌一道墙,两侧的水停在过期状态;把水下的一块石头
     * 清掉,那个洞不会自己被水填上。踢一脚只需要在外壳上做,内部全是刚放好的方块。
     *
     * <p>只在"连带清空"那档做:低档位不清场,挖不出会漏水的空腔。
     */
    void nudgeSurroundingWater(BlockPos siteMin, BlockPos siteMax) {
        if (r.replaceMode != ReplaceMode.REPLACE_EMPTY
                || !(player.level() instanceof ServerLevel level)) {
            return;
        }
        BlockPos.betweenClosedStream(siteMin.offset(-1, -1, -1), siteMax.offset(1, 1, 1))
                .filter(pos -> pos.getX() < siteMin.getX() || pos.getX() > siteMax.getX()
                        || pos.getY() < siteMin.getY() || pos.getY() > siteMax.getY()
                        || pos.getZ() < siteMin.getZ() || pos.getZ() > siteMax.getZ())
                .filter(pos -> level.isLoaded(pos) && level.getFluidState(pos).is(Fluids.WATER))
                .forEach(pos -> level.scheduleTick(pos.immutable(), Fluids.WATER,
                        Fluids.WATER.getTickDelay(level)));
    }
}
