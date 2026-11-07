package com.dwinovo.numen.pathing.drive;

import java.util.ArrayList;
import java.util.List;

import com.dwinovo.numen.pathing.world.Semantics;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;

/**
 * 这次导航里身体真在水下憋过的气:眼睛换不了气(第 0 层 {@link Semantics#breathless},与规划算憋气同一个判据)起、到眼睛
 * 出水止算一段,记下从哪一格下去、在哪一格出来、憋了几刻、氧气最低到过多少。只按身体的真实状态记,不看计划;随实际账一起
 * 交出,宿主照它告诉模型路上潜过哪几段水。
 */
public final class DiveLog {

    /**
     * 一段水下。
     *
     * @param from      眼睛没进水时脚所在的格
     * @param to        眼睛出水时(或导航收场时还在水下)脚所在的格
     * @param ticks     憋了几刻
     * @param lowestAir 这一段里氧气最低到过多少(原版 {@code getAirSupply})
     * @param maxAir    氧气上限
     */
    public record Dive(BlockPos from, BlockPos to, int ticks, int lowestAir, int maxAir) {}

    private final List<Dive> dives = new ArrayList<>();
    /** 在水下时这一段从哪儿下去的;不在水下为 null。 */
    private BlockPos from;
    private BlockPos last;
    private int ticks;
    private int lowest;

    /** 看这一刻的身体;一段水下在这一刻结束时交出它,否则 null。 */
    Dive observe(ServerPlayer body) {
        boolean under = Semantics.breathless(body.level(), body.getX(), body.getEyeY(), body.getZ());
        if (under) {
            if (from == null) {
                from = body.blockPosition();
                ticks = 0;
                lowest = body.getAirSupply();
            }
            ticks++;
            lowest = Math.min(lowest, body.getAirSupply());
            last = body.blockPosition();
            return null;
        }
        if (from == null) {
            return null;
        }
        Dive dive = new Dive(from, body.blockPosition(), ticks, lowest, body.getMaxAirSupply());
        dives.add(dive);
        from = null;
        return dive;
    }

    /** 到此刻为止的每一段,按先后;此刻还在水下的那一段算到此刻为止。 */
    List<Dive> dives(ServerPlayer body) {
        List<Dive> out = new ArrayList<>(dives);
        if (from != null) {
            out.add(new Dive(from, last, ticks, lowest, body.getMaxAirSupply()));
        }
        return out;
    }
}
