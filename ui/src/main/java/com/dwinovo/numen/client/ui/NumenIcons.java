package com.dwinovo.numen.client.ui;

/**
 * 图标是像素稿,不是字形。
 *
 * <p>每个图标就是一张 {@code '#'} 画的小图,行是 y、列是 x,看代码就能看出画的是什么,
 * 改也直接改那几行——比一串 {@code fillRect} 或一堆三角函数好认。画的时候按图形外沿补一圈
 * 一像素暗边,所以放在深底、浅底、方块贴图上都还认得出形状。
 *
 * <p>为什么不用贴图:{@code ui} 模块是纯 JVM 的,画布只认矩形与文字(见 {@link IDrawSurface}),
 * 碰不到 Minecraft 的资源系统。这也意味着图标在单元测试里能直接断言。
 */
public final class NumenIcons {

    private NumenIcons() {}

    /** 描边色:压在图形外沿的一圈暗边。 */
    private static final int OUTLINE = 0xFF1F1F1F;

    /** 改:一支斜着的铅笔,笔尖朝左下。 */
    public static final boolean[][] PENCIL = mask(
            "...........",
            "...........",
            "........##.",
            ".......###.",
            "......####.",
            ".....####..",
            "....####...",
            "...####....",
            "..####.....",
            "..##.......",
            "...........");

    /** 删:垃圾桶。盖与桶身之间空一行、桶身上挖两道竖纹——空出来的格子被描边填暗,成了线。 */
    public static final boolean[][] TRASH = mask(
            "...........",
            "....###....",
            ".#########.",
            "...........",
            "..#######..",
            "..##.#.##..",
            "..##.#.##..",
            "..##.#.##..",
            "..##.#.##..",
            "...#####...",
            "...........");

    /** 复制:前后两张纸,中间空一格好让描边把它们分开。 */
    public static final boolean[][] COPY = mask(
            ".............",
            ".#######.....",
            ".#######.....",
            ".#######.....",
            ".###.........",
            ".###.#######.",
            ".###.#######.",
            ".###.#######.",
            ".....#######.",
            ".....#######.",
            ".....#######.",
            ".....#######.",
            ".............");

    /** 这张稿子多少格见方(图标都是正方的)。 */
    public static int size(boolean[][] icon) {
        return icon.length;
    }

    /** 把稿子画在 (x,y):图形用给的颜色,外沿补一圈暗边。 */
    public static void draw(IDrawSurface s, boolean[][] icon, int x, int y, int color) {
        int n = icon.length;
        for (int gx = 0; gx < n; gx++) {
            for (int gy = 0; gy < n; gy++) {
                if (icon[gx][gy]) {
                    s.fillRect(x + gx, y + gy, 1, 1, color);
                } else if (touches(icon, gx, gy)) {
                    s.fillRect(x + gx, y + gy, 1, 1, OUTLINE);
                }
            }
        }
    }

    /** (gx,gy) 不在图形上但与图形八邻接——该补暗边的格子。 */
    private static boolean touches(boolean[][] icon, int gx, int gy) {
        int n = icon.length;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                int nx = gx + dx, ny = gy + dy;
                if (nx >= 0 && nx < n && ny >= 0 && ny < n && icon[nx][ny]) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 稿子转掩码:行是 y、列是 x,读成 {@code [x][y]} 与画布坐标同序。 */
    private static boolean[][] mask(String... rows) {
        int n = rows.length;
        boolean[][] m = new boolean[n][n];
        for (int y = 0; y < n; y++) {
            for (int x = 0; x < n; x++) {
                m[x][y] = rows[y].charAt(x) == '#';
            }
        }
        return m;
    }
}
