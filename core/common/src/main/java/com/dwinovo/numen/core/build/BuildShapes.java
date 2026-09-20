package com.dwinovo.numen.core.build;

import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 建造几何:通用形状算子,全是无状态纯函数,只产出格位。
 *
 * <p>这里<b>只有几何,没有风格</b>。屋顶该怎么举架、脊该用什么料、檐口收多薄,
 * 全是建筑知识,住在技能文档里;代码只负责"把这些格子算出来"。此前这里住着一整套
 * 中式屋顶引擎(五种形制各一条分支),那是把内容写进了机制——换一种风格就得改代码,
 * 而模型想要的第六种永远没有。
 *
 * <p>格子怎么变成方块状态,见 {@link BuildPalette};一张字符网格怎么变成一层,
 * 见 {@link #layerCells}。
 */
public final class BuildShapes {

    private BuildShapes() {}

    /**
     * 一次调用展开后的总格数上限。
     *
     * <p>此前是 4096,而一栋正常房子 5000~8000 格——等于逼着整栋建筑拆成好几次
     * 调用。生存模式的盘料只盘当前这一批,拆开就意味着墙已经砌好了才发现屋顶的
     * 料不够,留下半成品空壳。<b>"整栋一次规划"和这个上限是绑死的</b>,要求前者
     * 就必须给够后者。
     */
    public static final int MAX_TOTAL_CELLS = 16384;

    /** 一层网格里的一格:位置 + 图例里的那个字符。 */
    public record CharCell(BlockPos pos, char key) {}

    /**
     * 形状展开为格集(去重、上限封顶)。公开静态,测试直接验几何。
     *
     * <p>只剩三个形状:线、圆柱、球。长方体、墙圈、撒点都归 {@link #layerCells}——
     * 一张网格能表达的远不止长方体,而多一个算子就多一份要维护的语义。
     */
    public static List<BlockPos> shapeCells(String shape, boolean hollow,
                                       int x1, int y1, int z1,
                                       Integer x2, Integer y2, Integer z2,
                                       Integer radius, Integer height) {
        Set<BlockPos> out = new LinkedHashSet<>();
        switch (shape == null ? "" : shape) {
            case "line" -> {
                int bx = req(x2, "x2"), by = req(y2, "y2"), bz = req(z2, "z2");
                int steps = Math.max(1, Math.max(Math.abs(bx - x1),
                        Math.max(Math.abs(by - y1), Math.abs(bz - z1))));
                for (int i = 0; i <= steps; i++) {
                    add(out, Math.round(x1 + (bx - x1) * (float) i / steps),
                            Math.round(y1 + (by - y1) * (float) i / steps),
                            Math.round(z1 + (bz - z1) * (float) i / steps));
                }
            }
            case "cylinder" -> {
                int r = req(radius, "radius");
                int h = Math.max(1, height == null ? 1 : height);
                double outer = (r + 0.5) * (r + 0.5);
                double inner = (r - 0.5) * (r - 0.5);
                for (int y = y1; y < y1 + h; y++) {
                    for (int dx = -r; dx <= r; dx++) {
                        for (int dz = -r; dz <= r; dz++) {
                            double d = dx * dx + dz * dz;
                            if (d > outer || (hollow && d < inner)) {
                                continue;
                            }
                            add(out, x1 + dx, y, z1 + dz);
                        }
                    }
                }
            }
            case "sphere" -> {
                int r = req(radius, "radius");
                double outer = (r + 0.5) * (r + 0.5);
                double inner = (r - 0.5) * (r - 0.5);
                for (int dy = -r; dy <= r; dy++) {
                    for (int dx = -r; dx <= r; dx++) {
                        for (int dz = -r; dz <= r; dz++) {
                            double d = dx * dx + dy * dy + dz * dz;
                            if (d > outer || (hollow && d < inner)) {
                                continue;
                            }
                            add(out, x1 + dx, y1 + dy, z1 + dz);
                        }
                    }
                }
            }
            default -> throw new IllegalArgumentException("shape must be line, cylinder or sphere");
        }
        if (out.isEmpty()) {
            throw new IllegalArgumentException("shape resolved to zero cells");
        }
        if (out.size() > MAX_TOTAL_CELLS) {
            throw new IllegalArgumentException("shape has " + out.size() + " cells, exceeding "
                    + MAX_TOTAL_CELLS + "; split it into smaller calls");
        }
        return new ArrayList<>(out);
    }

    /**
     * 一张字符网格铺成一层(或从 {@code y1} 到 {@code y2} 每一层各铺一张)。
     *
     * <p>网格的方向定死:<b>第一行在 z0,行沿 +z 推进;行内第一个字符在 x0,沿 +x 推进</b>——
     * 也就是俯视图上"上北下南、左西右东"的那张图。写法与原版 {@code /fill} 的关系:
     * 一张全是同一个字符的网格就是 fill,中空的一圈就是墙,隔一格一个字符就是撒点,
     * 而 L 形、T 形、拱门、窗花这些 fill 根本表达不了的,它一样画得出来。
     *
     * <p>空格与 {@code '.'} 是"这一格不管",不入格集——和"放空气(挖空)"是两件事,
     * 后者在图例里点名 {@code air}。
     */
    public static List<CharCell> layerCells(int x0, int y1, int y2, int z0, List<String> rows) {
        if (rows == null || rows.isEmpty()) {
            throw new IllegalArgumentException("layer needs rows");
        }
        int lo = Math.min(y1, y2);
        int hi = Math.max(y1, y2);
        List<CharCell> out = new ArrayList<>();
        for (int y = lo; y <= hi; y++) {
            for (int row = 0; row < rows.size(); row++) {
                String line = rows.get(row);
                if (line == null) {
                    continue;
                }
                for (int col = 0; col < line.length(); col++) {
                    char key = line.charAt(col);
                    if (key == ' ' || key == '.') {
                        continue;
                    }
                    out.add(new CharCell(new BlockPos(x0 + col, y, z0 + row), key));
                }
            }
        }
        if (out.isEmpty()) {
            throw new IllegalArgumentException("layer resolved to zero cells — every row was blank");
        }
        if (out.size() > MAX_TOTAL_CELLS) {
            throw new IllegalArgumentException("layer has " + out.size() + " cells, exceeding "
                    + MAX_TOTAL_CELLS + "; split it into smaller calls");
        }
        return out;
    }

    private static void add(Set<BlockPos> out, int x, int y, int z) {
        out.add(new BlockPos(x, y, z));
    }

    private static int req(Integer v, String name) {
        if (v == null) {
            throw new IllegalArgumentException(name + " is required");
        }
        return v;
    }
}
