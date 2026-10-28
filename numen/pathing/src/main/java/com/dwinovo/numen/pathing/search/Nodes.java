package com.dwinovo.numen.pathing.search;

import java.util.Arrays;

import com.dwinovo.numen.pathing.plan.Breath;
import com.dwinovo.numen.pathing.plan.Maneuver;
import com.dwinovo.numen.pathing.plan.Stance;

import it.unimi.dsi.fastutil.HashCommon;

/**
 * 一次搜索的全部节点:不建节点对象,每个节点是一个下标,各项事实放在平行数组里。节点由位置({@link net.minecraft.core.BlockPos#asLong}
 * 编码)、设了改动预算时到这儿已经改了几格、憋气的档三样确定——同一格,已改格数或憋气的档不同是另一个节点;开放寻址的
 * 哈希表把这三样映射到下标。
 *
 * <p>数组会随节点增多而换成更大的,所以别在调用之间留着数组的引用,每次都经字段取。只在跑这次搜索的线程上用。
 */
final class Nodes {

    private static final int NONE = -1;
    /** 起手能放多少个节点;不够就翻倍。 */
    private static final int INITIAL = 256;

    int size;
    /** 位置,{@link net.minecraft.core.BlockPos#asLong} 编码。 */
    long[] pos = new long[INITIAL];
    /** 设了改动预算时到这儿已经改了几格,没设为 0。 */
    int[] used = new int[INITIAL];
    /** 憋气的档({@link Breath#band})。 */
    int[] band = new int[INITIAL];
    /** 估价:到目标至少还要多少刻;到了就是 0。 */
    double[] h = new double[INITIAL];
    /** 已走的代价。 */
    double[] g = new double[INITIAL];
    /** 开放集的排序键:走到的代价加估价的加权倍数。 */
    double[] f = new double[INITIAL];
    /** 堆里排序用的总价:f 取整到 {@link OpenSet#TIE};入堆、改键时由堆更新。 */
    long[] key = new long[INITIAL];
    /** 走到这里的上一个节点;起点为 -1。 */
    int[] parent = new int[INITIAL];
    /** 在堆里的位置;不在堆里为 -1。 */
    int[] heapIndex = new int[INITIAL];
    /** 展开过:往外的每一步都判过了。没展开过、又有了代价的节点是边界。 */
    boolean[] expanded = new boolean[INITIAL];
    /** 展开时有一步读到了没加载的列、没能判断:这个节点往外的路没有看全,算边界。 */
    boolean[] holed = new boolean[INITIAL];
    /** 到达价已经付过:目标要求停下还要付价的节点,按"走到加停下"的总价再出一次堆才收。 */
    boolean[] paid = new boolean[INITIAL];
    /** 走到这里的那一步:走法在 {@code Moves.ALL} 里的下标,与方向在那个走法的 {@code headings()} 里的下标。 */
    byte[] move = new byte[INITIAL];
    byte[] heading = new byte[INITIAL];
    /** 走到这里那一步的代价(不含旧路折扣)。 */
    double[] viaCost = new double[INITIAL];
    /** 走到这里时身体怎么待着。 */
    Stance[] stance = new Stance[INITIAL];
    /** 走到这里时身体憋气的样子。 */
    Breath.Air[] air = new Breath.Air[INITIAL];
    /**
     * 走到这里的那一步的全套事实。精确判断算出来的节点当场带着;快判断算出来的节点没有它(也没有改动),交路线时才按同一步
     * 重新判一遍补上。
     */
    Maneuver[] via = new Maneuver[INITIAL];

    private int[] table = filled(2 * INITIAL);
    private int mask = 2 * INITIAL - 1;

    private int slot(long at, int used, int band) {
        return (int) HashCommon.mix(at * 31 + used * 1_000_003L + band) & mask;
    }

    private static int[] filled(int n) {
        int[] t = new int[n];
        Arrays.fill(t, NONE);
        return t;
    }

    /** 位置 {@code at}、已改 {@code used} 格、憋气 {@code band} 档的节点;还没有为 -1。 */
    int find(long at, int used, int band) {
        int slot = slot(at, used, band);
        for (int id = table[slot]; id != NONE; id = table[slot]) {
            if (this.pos[id] == at && this.used[id] == used && this.band[id] == band) {
                return id;
            }
            slot = (slot + 1) & mask;
        }
        return NONE;
    }

    /** 新建一个节点(调用方已经 {@link #find} 过没有):还没走到(代价无穷)、不在堆里。 */
    int add(long at, int used, int band, double estimate) {
        if (size == pos.length) {
            grow();
        }
        int id = size++;
        pos[id] = at;
        this.used[id] = used;
        this.band[id] = band;
        h[id] = estimate;
        g[id] = Double.POSITIVE_INFINITY;
        parent[id] = NONE;
        heapIndex[id] = NONE;
        int slot = slot(at, used, band);
        while (table[slot] != NONE) {
            slot = (slot + 1) & mask;
        }
        table[slot] = id;
        if (size * 2 > table.length) {
            rehash();
        }
        return id;
    }

    private void grow() {
        int n = pos.length * 2;
        pos = Arrays.copyOf(pos, n);
        used = Arrays.copyOf(used, n);
        band = Arrays.copyOf(band, n);
        h = Arrays.copyOf(h, n);
        g = Arrays.copyOf(g, n);
        f = Arrays.copyOf(f, n);
        key = Arrays.copyOf(key, n);
        parent = Arrays.copyOf(parent, n);
        heapIndex = Arrays.copyOf(heapIndex, n);
        expanded = Arrays.copyOf(expanded, n);
        holed = Arrays.copyOf(holed, n);
        paid = Arrays.copyOf(paid, n);
        move = Arrays.copyOf(move, n);
        heading = Arrays.copyOf(heading, n);
        viaCost = Arrays.copyOf(viaCost, n);
        stance = Arrays.copyOf(stance, n);
        air = Arrays.copyOf(air, n);
        via = Arrays.copyOf(via, n);
    }

    private void rehash() {
        table = filled(table.length * 2);
        mask = table.length - 1;
        for (int id = 0; id < size; id++) {
            int slot = slot(pos[id], used[id], band[id]);
            while (table[slot] != NONE) {
                slot = (slot + 1) & mask;
            }
            table[slot] = id;
        }
    }
}
