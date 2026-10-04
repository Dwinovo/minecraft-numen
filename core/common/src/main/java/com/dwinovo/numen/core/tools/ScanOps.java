package com.dwinovo.numen.core.tools;

import com.dwinovo.numen.core.scan.BlockGroups;
import com.dwinovo.numen.core.scan.BlockScan;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.sdk.BlockAt;
import com.dwinovo.numen.sdk.Doc;
import com.dwinovo.numen.sdk.Methods;
import com.dwinovo.numen.sdk.Pending;
import net.minecraft.world.level.block.Block;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import com.dwinovo.numen.core.scan.BlockSearch;

/**
 * {@code numen.scan.blocks} 的实现(登记在 {@link com.dwinovo.numen.core.tools.perception.ScanApi})。看是按刻分片的
 * ({@link BlockScan}),看完的那一刻才有值。
 *
 * <p>结果是一串团({@link Cluster}):相连的命中格(对角也算)成一团,由近及远;每团带着它的每一格(方块与位置,近的在前)、最近的那一格与格数。
 * 只是看,什么也不存:要再用,程序就拿着这份结果,或再看一次。
 */
public final class ScanOps {

    private static final int MIN_RADIUS = 1;

    private ScanOps() {}

    /** 一团:扫描找到的相连的方块。方法(filter、minus)写在模块 {@code numen.scan} 里。 */
    @Doc("Touching blocks a scan found (diagonals count): every block, nearest first.")
    @Methods("numen.scan")
    public record Cluster(@Doc("Every block of it, nearest first.") List<BlockAt> blocks,
                          @Doc("The block nearest to where you stood.") BlockAt nearest,
                          @Doc("How many blocks.") int count) {}

    /**
     * 看一次,看完那一刻有值:全部的团,由近及远。没看全时(没加载的区块、搜到上限)那句话随值交回,写进程序的回执:只交一串团,
     * 分不清"半径里没有铁"和"半径里大半没看",她每次都会读成前一种。
     */
    public static Pending<List<Cluster>> scan(NumenPlayer self, int radius, Set<Block> targets) {
        Pending<List<Cluster>> out = Pending.create();
        BlockScan.start(self, Math.clamp(radius, MIN_RADIUS, BlockScan.MAX_RADIUS), targets, found -> {
            String note = coverageNote(found.coverage());
            if (note != null) {
                out.report("Only part of the radius was read: " + note + ".");
            }
            out.complete(found.groups().stream().map(ScanOps::cluster).toList());
        });
        return out;
    }

    /** 一团:每一格是一个 Block(近的在前),最近的那一格,格数。 */
    static Cluster cluster(BlockGroups.Group group) {
        List<BlockAt> blocks = new ArrayList<>();
        group.cells().forEach((pos, state) -> blocks.add(BlockAt.of(pos, state)));
        return new Cluster(blocks, BlockAt.of(group.nearest(), group.cells().get(group.nearest())),
                group.cells().size());
    }

    /**
     * 这次看实际看到了哪些,说给她的话——看全了是 null。
     */
    static String coverageNote(BlockSearch.ScanResult res) {
        List<String> notes = new ArrayList<>(3);
        String capped = res.sectionCapNote();
        if (capped != null) {
            notes.add(capped);
        }
        if (res.collectCapHit()) {
            notes.add("stopped at " + BlockSearch.MAX_COLLECT + " matching blocks — only the part nearest you "
                    + "was read and clusters at its edge may be cut off; scan a smaller radius");
        }
        if (res.columnsUnloaded() > 0) {
            notes.add(res.columnsUnloaded() + " of " + res.columnsTotal() + " chunk columns in this "
                    + "radius are not loaded, so they were not searched — blocks out there are "
                    + "UNKNOWN, not absent; walk that way and scan again to find out");
        }
        return notes.isEmpty() ? null : String.join("; ", notes);
    }
}
