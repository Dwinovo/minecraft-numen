package com.dwinovo.numen.entity;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;

import java.util.Comparator;

/**
 * Keeps a small, self-expiring pad of chunks loaded and ticking around a companion,
 * so a {@link NumenPlayer} can path / mine / fight away from any real player WITHOUT
 * being a full client viewer.
 *
 * <h2>Why this exists</h2>
 * A vanilla {@link net.minecraft.server.level.ServerPlayer} loads chunks through
 * {@code DistanceManager.addPlayer}, which {@code ChunkMap.updatePlayerStatus} gates on
 * {@code !skipPlayer(player)}. {@link com.dwinovo.numen.mixin.ChunkMapCompanionMixin} forces
 * {@code skipPlayer == true} for companions — that strips the wasted client chunk-tracking /
 * packet machinery (a fake player has no client), but as a side effect also strips the player
 * loading ticket. This class adds the loading back, but <b>bounded</b>: a {@value #RADIUS}-chunk
 * region ticket (centre {@code ENTITY_TICKING}, a 5×5 loaded pad) instead of a real player's
 * whole simulation-distance sphere. Remote autonomy is preserved; the per-companion footprint is
 * a fixed, small pad rather than full-player weight.
 *
 * <h2>Self-cleaning — no explicit release</h2>
 * The ticket carries a {@value #TIMEOUT_TICKS}-tick timeout and is re-stamped on every
 * {@link #refresh}. {@code DistanceManager.addTicket} resets a re-added ticket's created-tick, so
 * while the companion ticks the pad stays alive; the instant it stops ticking — dormancy, death,
 * despawn, chunk unload, even a crash — the ticket simply times out. Nothing is leaked and nothing
 * has to be remembered and removed. Companions sharing a chunk share one ticket, kept alive by
 * whoever is still refreshing it, so a departing companion never yanks the pad out from under one
 * that stayed.
 */
public final class CompanionChunkLoader {

    /** Region-ticket radius. i = 2 → centre chunk level 31 (ENTITY_TICKING); a 5×5 pad is loaded. */
    private static final int RADIUS = 2;

    /**
     * Ticket timeout in ticks. The refresh re-adds the ticket at half this interval (or immediately
     * on a chunk crossing), so a live companion always renews with a full margin; 40 ticks also
     * bounds how long a departed companion's pad lingers before it unloads (~2 s).
     */
    private static final int TIMEOUT_TICKS = 40;

    private static final TicketType<ChunkPos> TICKET =
            TicketType.create("numen_companion", Comparator.comparingLong(ChunkPos::toLong), TIMEOUT_TICKS);

    private CompanionChunkLoader() {}

    /** 运行期总开关(诊断 A/B 用):关掉后 {@link #refresh} 变空操作,已有票据 40 tick 内自然过期。
     *  服务端主线程读写;volatile 仅为命令线程可见性兜底。 */
    public static volatile boolean enabled = true;

    /** 距超时刷新的间隔:取 {@link #TIMEOUT_TICKS} 的一半,对到期留一整倍裕量。 */
    private static final int REFRESH_TICKS = TIMEOUT_TICKS / 2;

    /** 每同伴的 pad 状态:上一次的中心 chunk + 距下一次超时续票的倒计时。服务端线程独占;
     *  条目数 ≤ 本次运行出现过的同伴数,不清理。 */
    private static final class PadState {
        long chunk = Long.MIN_VALUE;
        int countdown;
    }

    private static final java.util.Map<java.util.UUID, PadState> states = new java.util.HashMap<>();

    /**
     * Maintain the loader pad. Called every server tick, but the actual ticket op is amortised:
     * a ticket is (re-)added only when the companion CROSSED into a new chunk (re-centre the pad
     * immediately) or the {@link #REFRESH_TICKS} countdown elapsed (keep the timeout alive with a
     * full margin). In the steady state this is one countdown decrement per tick and one ticket op
     * per {@value #REFRESH_TICKS} ticks — the ticket's own timeout still guarantees cleanup if the
     * companion stops ticking for any reason.
     */
    public static void refresh(NumenPlayer companion) {
        if (!enabled) {
            return;
        }
        if (companion.level() instanceof ServerLevel level) {
            ChunkPos pos = companion.chunkPosition();
            long packed = pos.toLong();
            PadState st = states.computeIfAbsent(companion.getUUID(), k -> new PadState());
            boolean crossed = st.chunk != packed;
            if (!crossed && --st.countdown > 0) {
                return;   // 票据仍在裕量期内,无事可做
            }
            if (crossed) {
                // pad 中心迁移打点(debug 级:诊断用,走路的同伴几秒跨一个 chunk,不该刷正式日志;
                // 需要时开 debug.log 或调日志级别即可看到)。
                com.dwinovo.numen.Constants.LOG.debug("[numen-pad] {} pad center -> chunk {},{}",
                        companion.getName().getString(), pos.x, pos.z);
            }
            st.chunk = packed;
            st.countdown = REFRESH_TICKS;
            level.getChunkSource().addRegionTicket(TICKET, pos, RADIUS, pos);
        }
    }
}
