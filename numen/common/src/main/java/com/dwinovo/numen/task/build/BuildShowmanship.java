package com.dwinovo.numen.task.build;

import com.dwinovo.numen.api.entity.NumenPlayer;
import com.dwinovo.numen.api.entity.Controls;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * 施工的演出:蹲起,以及手放不会自己产生的那些——直接写的格上的转头、挥手、手到落点的粒子、落位声。手放的格不在这里演:
 * 转向、挥手、落位声、邻居反应是原版放置流程自己的({@link BuildPlacer})。只管"看起来像有人在干活",不碰任何施工判定与账目——
 * 删掉整个类,房子照样盖得出来,只是直接写的格看着像作弊。
 *
 * <h2>表演不改变世界</h2>
 * 它手里只有身体的输入(转头、挥手、蹲)和一份只读的视图:没有挖掘器,没有导航,没有放置的入口,也不挪身体——她站在原地放
 * 够得着的格,走到哪儿是脚本的事。
 */
final class BuildShowmanship {

    /** 挥臂间隔:与原版挥臂动画一轮的长度对齐。 */
    private static final int SWING_PERIOD_TICKS = 6;
    /** 手到落点那道粒子的采样点数。 */
    private static final int CONJURE_TRAIL_SAMPLES = 6;
    /** 每批最多冒几处粒子——整栋房子逐格发粒子会把客户端打垮。 */
    private static final int PARTICLE_BUDGET_PER_BATCH = 3;

    private final NumenPlayer player;
    private final BuildInventory inv;

    private int swingCooldown;
    /** 落位批次的高度要她蹲下(跨 tick 保持)。 */
    private boolean crouching;

    BuildShowmanship(NumenPlayer player, BuildInventory inv) {
        this.player = player;
        this.inv = inv;
    }

    /**
     * 站住不走。站着时蹲不蹲由落位批次的高度定,而且每刻都照这个定:落位只在批次刻发生,若别的刻复位成站立,
     * 她会一蹲一起地抖。
     */
    void stand() {
        player.controls().stop();
        player.controls().set(Controls.Key.SNEAK, crouching);
    }

    /**
     * 演出一批:低处蹲下、高处站直(手放与直接写的格都算,玩家都熟的贴边放方块的姿势);其中直接写的格({@code written})
     * 再朝它们的中心转头、举起对应方块、挥手,冒方块碎屑,放落位声。粒子按批限量——整栋房子逐格发粒子会把客户端打垮。
     *
     * @param touched 这一批动过的格(手放的、直接写的)
     * @param written 其中直接写的格;全是手放时为空
     * @param sample  直接写的那种方块
     */
    void performWork(List<BlockPos> touched, List<BlockPos> written, BlockState sample) {
        crouching = center(touched).y < player.getY() + 0.6;
        if (written.isEmpty()) {
            return;
        }
        Vec3 centre = center(written);
        player.look().at(centre);   // 视角按鼠标像素取整转向目标点,和寻路同一套转头
        // 挥手按动画节拍走,不按落位节拍。原版一轮挥臂约 6 刻,而落位可以每刻好几格
        // ——每批都触发就是手臂永远画不完一个来回,看起来是抽搐不是干活。
        boolean swung = --swingCooldown <= 0;
        if (swung) {
            player.swing(InteractionHand.MAIN_HAND);
            swingCooldown = SWING_PERIOD_TICKS;
        }
        if (sample != null) {
            int slot = inv.findSlot(sample.getBlock().asItem(), true);
            if (slot >= 0) {
                player.hotbar().hold(slot);
            }
        }
        if (!(player.level() instanceof ServerLevel level) || sample == null) {
            return;
        }
        if (swung) {
            emitConjureTrail(level, centre);
        }
        int spouts = Math.min(PARTICLE_BUDGET_PER_BATCH, written.size());
        int step = Math.max(1, written.size() / spouts);
        for (int i = 0; i < written.size(); i += step) {
            BlockPos pos = written.get(i);
            level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, sample),
                    pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5,
                    4, 0.28, 0.28, 0.28, 0.0);
        }
        var sound = sample.getSoundType().getPlaceSound();
        BlockPos at = written.get(written.size() / 2);
        level.playSound(null, at, sound, SoundSource.BLOCKS, 0.7f, 0.9f + player.getRandom().nextFloat() * 0.2f);
    }

    private static Vec3 center(List<BlockPos> cells) {
        Vec3 sum = Vec3.ZERO;
        for (BlockPos pos : cells) {
            sum = sum.add(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
        }
        return sum.scale(1.0 / cells.size());
    }

    /** 收工的一把庆祝粒子,撒在工地正上方。 */
    void celebrate(BlockPos siteMin, BlockPos siteMax) {
        if (player.level() instanceof ServerLevel level) {
            level.sendParticles(ParticleTypes.HAPPY_VILLAGER,
                    (siteMin.getX() + siteMax.getX()) / 2.0 + 0.5,
                    siteMax.getY() + 1.0,
                    (siteMin.getZ() + siteMax.getZ()) / 2.0 + 0.5,
                    24, (siteMax.getX() - siteMin.getX()) / 3.0 + 1.0, 1.0,
                    (siteMax.getZ() - siteMin.getZ()) / 3.0 + 1.0, 0.0);
        }
    }

    /**
     * 从她手上飞向落点的一道粒子。
     *
     * <p>直接写的格没有点击:方块凭空出现、她在旁边挥手,这两件事之间没有任何可见的联系。把因果画出来之后,
     * 隔空落位才读得成手艺而不是开挂。一次挥臂一道,跟着挥臂节拍走,不会刷屏。
     */
    private void emitConjureTrail(ServerLevel level, Vec3 to) {
        Vec3 from = player.getEyePosition().add(player.getLookAngle().scale(0.6)).add(0, -0.3, 0);
        Vec3 step = to.subtract(from).scale(1.0 / (CONJURE_TRAIL_SAMPLES + 1));
        for (int i = 1; i <= CONJURE_TRAIL_SAMPLES; i++) {
            Vec3 p = from.add(step.scale(i));
            level.sendParticles(ParticleTypes.END_ROD, p.x, p.y, p.z, 1, 0.0, 0.0, 0.0, 0.0);
        }
    }
}
