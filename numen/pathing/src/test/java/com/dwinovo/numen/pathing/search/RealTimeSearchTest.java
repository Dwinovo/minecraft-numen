package com.dwinovo.numen.pathing.search;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Random;

import org.junit.jupiter.api.Test;

/**
 * 选半程去处({@link HandOver#order})与学习({@link Learning#raise})合起来,按 LSS-LRTA* [Koenig &amp; Sun 2009] 走一趟:四连通方格、
 * 每步价 1、估价是曼哈顿距离,每次从身体所在的格做 {@code k} 个节点的有界 A*,没搜到目标就抬估价、走到选出的边界节点。
 * 不经 Minecraft:只看有路的图上(U 形屋子、死胡同、柱子森林、迷宫)这一对规则一定走得到。目标到不了时它不会自己停,
 * 一趟靠总节点数收场({@code drive.Allowance}),不在这里。
 */
class RealTimeSearchTest {

    /** 一趟走下来:到没到、走了几步。 */
    private record Trip(boolean arrived, int moves) {}

    /** {@code #} 是墙,{@code S} 起点,{@code G} 目标。 */
    private static final class Grid {

        final int w;
        final int h;
        final boolean[] wall;
        int start;
        int goal;

        Grid(String... rows) {
            h = rows.length;
            w = rows[0].length();
            wall = new boolean[w * h];
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    char c = rows[y].charAt(x);
                    wall[y * w + x] = c == '#';
                    if (c == 'S') {
                        start = y * w + x;
                    } else if (c == 'G') {
                        goal = y * w + x;
                    }
                }
            }
        }

        Grid(int w, int h, double density, long seed) {
            this.w = w;
            this.h = h;
            wall = new boolean[w * h];
            Random random = new Random(seed);
            for (int i = 0; i < wall.length; i++) {
                wall[i] = random.nextDouble() < density;
            }
            start = (h / 2) * w;
            goal = (h / 2) * w + w - 1;
            wall[start] = false;
            wall[goal] = false;
        }

        double h0(int s) {
            return Math.abs(s % w - goal % w) + Math.abs(s / w - goal / w);
        }

        int[] next(int s) {
            int x = s % w;
            int y = s / w;
            int[] out = new int[4];
            int n = 0;
            if (x > 0 && !wall[s - 1]) out[n++] = s - 1;
            if (x < w - 1 && !wall[s + 1]) out[n++] = s + 1;
            if (y > 0 && !wall[s - w]) out[n++] = s - w;
            if (y < h - 1 && !wall[s + w]) out[n++] = s + w;
            return Arrays.copyOf(out, n);
        }
    }

    /** 跑一趟:每次有界 A* 展开 {@code k} 个格;走满 {@code cap} 步还没到算没到。边界一个都没有就是到不了。 */
    private static Trip run(Grid grid, int k, int cap) {
        int cells = grid.w * grid.h;
        double[] h = new double[cells];
        for (int s = 0; s < cells; s++) {
            h[s] = grid.h0(s);
        }
        int at = grid.start;
        int moves = 0;
        while (at != grid.goal && moves < cap) {
            // 有界 A*:节点就是格,局部下标按生成的先后编
            int[] local = new int[cells];
            Arrays.fill(local, -1);
            List<Integer> cell = new ArrayList<>();
            List<Double> g = new ArrayList<>();
            List<Integer> parent = new ArrayList<>();
            List<Boolean> closed = new ArrayList<>();
            PriorityQueue<double[]> open = new PriorityQueue<>(Comparator.<double[]>comparingDouble(e -> e[0]).thenComparingDouble(e -> e[1]));
            local[at] = 0;
            cell.add(at);
            g.add(0.0);
            parent.add(-1);
            closed.add(false);
            open.add(new double[] {h[at], h[at], 0});
            List<int[]> edges = new ArrayList<>();
            int expansions = 0;
            int reached = -1;
            while (!open.isEmpty()) {
                double[] top = open.peek();
                int n = (int) top[2];
                if (closed.get(n) || top[0] > g.get(n) + h[cell.get(n)]) {
                    open.poll();
                    continue;
                }
                if (cell.get(n) == grid.goal) {
                    reached = n;
                    break;
                }
                if (expansions >= k) {
                    break;
                }
                open.poll();
                closed.set(n, true);
                expansions++;
                for (int s : grid.next(cell.get(n))) {
                    int m = local[s];
                    if (m < 0) {
                        m = cell.size();
                        local[s] = m;
                        cell.add(s);
                        g.add(Double.POSITIVE_INFINITY);
                        parent.add(-1);
                        closed.add(false);
                    }
                    edges.add(new int[] {n, m});
                    if (g.get(n) + 1 < g.get(m)) {
                        g.set(m, g.get(n) + 1);
                        parent.set(m, n);
                        open.add(new double[] {g.get(m) + h[s], h[s], m});
                    }
                }
            }
            int target;
            if (reached >= 0) {
                target = reached;
            } else {
                List<HandOver.Candidate> frontier = new ArrayList<>();
                for (int n = 0; n < cell.size(); n++) {
                    if (!closed.get(n) && Double.isFinite(g.get(n))) {
                        int s = cell.get(n);
                        frontier.add(new HandOver.Candidate(n, g.get(n), h[s], false));
                    }
                }
                if (frontier.isEmpty()) {
                    return new Trip(false, moves);
                }
                target = HandOver.order(frontier).get(0).node();
                int size = cell.size();
                boolean[] expanded = new boolean[size];
                double[] hs = new double[size];
                for (int n = 0; n < size; n++) {
                    expanded[n] = closed.get(n);
                    hs[n] = h[cell.get(n)];
                }
                int[] from = new int[edges.size()];
                int[] to = new int[edges.size()];
                double[] cost = new double[edges.size()];
                for (int e = 0; e < edges.size(); e++) {
                    from[e] = edges.get(e)[0];
                    to[e] = edges.get(e)[1];
                    cost[e] = 1;
                }
                double[] raised = Learning.raise(expanded, hs, size, from, to, cost, edges.size());
                for (int n = 0; n < size; n++) {
                    h[cell.get(n)] = raised[n];
                }
            }
            List<Integer> path = new ArrayList<>();
            for (int n = target; n > 0; n = parent.get(n)) {
                path.add(cell.get(n));
            }
            for (int i = path.size() - 1; i >= 0 && moves < cap; i--) {
                at = path.get(i);
                moves++;
            }
        }
        return new Trip(at == grid.goal, moves);
    }

    /** 开口朝着身体的 U 形屋子,目标在屋后:估价把身体一直往屋里引。 */
    private static Grid trap() {
        return new Grid(
                "..............................",
                "..............................",
                "..........##########..........",
                "...................#..........",
                "...................#..........",
                "...................#..........",
                "...S...............#.......G..",
                "...................#..........",
                "...................#..........",
                "...................#..........",
                "..........##########..........",
                "..............................",
                "..............................");
    }

    /** 朝目标伸出去的长死胡同,出口在身后:真正的路要先退出来、绕过整面墙。 */
    private static Grid deadEnd() {
        return new Grid(
                "........................................",
                "........................................",
                "........................................",
                ".....############################.......",
                ".....S..........................#....G..",
                ".....############################.......",
                "........................................",
                "........................................",
                "........................................");
    }

    /** 迷宫({@code cw} × {@code ch} 个房间,深度优先打通):路是绕的,估价指的方向多半是死路。起点在西边正中,目标在东边正中。 */
    private static Grid maze(int cw, int ch, long seed) {
        int w = cw * 2 + 1;
        int h = ch * 2 + 1;
        char[][] g = new char[h][w];
        for (char[] row : g) {
            Arrays.fill(row, '#');
        }
        Random random = new Random(seed);
        boolean[] seen = new boolean[cw * ch];
        List<Integer> stack = new ArrayList<>(List.of(0));
        seen[0] = true;
        g[1][1] = '.';
        int[][] steps = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        while (!stack.isEmpty()) {
            int c = stack.get(stack.size() - 1);
            int cx = c % cw;
            int cy = c / cw;
            List<int[]> fresh = new ArrayList<>();
            for (int[] d : steps) {
                int nx = cx + d[0];
                int ny = cy + d[1];
                if (nx >= 0 && ny >= 0 && nx < cw && ny < ch && !seen[ny * cw + nx]) {
                    fresh.add(new int[] {nx, ny});
                }
            }
            if (fresh.isEmpty()) {
                stack.remove(stack.size() - 1);
                continue;
            }
            int[] n = fresh.get(random.nextInt(fresh.size()));
            seen[n[1] * cw + n[0]] = true;
            g[cy + n[1] + 1][cx + n[0] + 1] = '.';
            g[n[1] * 2 + 1][n[0] * 2 + 1] = '.';
            stack.add(n[1] * cw + n[0]);
        }
        int middle = ch / 2 * 2 + 1;
        g[middle][1] = 'S';
        g[middle][w - 2] = 'G';
        String[] rows = new String[h];
        for (int y = 0; y < h; y++) {
            rows[y] = new String(g[y]);
        }
        return new Grid(rows);
    }

    @Test
    void outOfAUShapedRoomOpeningTowardTheBody() {
        Trip trip = run(trap(), 4, 100_000);
        assertTrue(trip.arrived(), trip.toString());
    }

    @Test
    void outOfADeadEndCorridor() {
        Trip trip = run(deadEnd(), 4, 100_000);
        assertTrue(trip.arrived(), trip.toString());
    }

    /** 柱子森林(随机两成、三成格子是柱子)与迷宫:有路的每一张都走到。 */
    @Test
    void throughPillarForestsAndMazesEveryReachableGoalIsReached() {
        int reachable = 0;
        for (double density : new double[] {0.2, 0.3}) {
            for (long seed = 1; seed <= 20; seed++) {
                Grid forest = new Grid(40, 30, density, seed);
                if (!connected(forest)) {
                    continue;
                }
                reachable++;
                Trip trip = run(forest, 8, 1_000_000);
                assertTrue(trip.arrived(), "柱子 " + density + " 种子 " + seed + " 没到:" + trip);
            }
        }
        assertTrue(reachable >= 10, "有路的图太少:" + reachable);
        for (long seed = 1; seed <= 10; seed++) {
            Trip trip = run(maze(15, 10, seed), 4, 1_000_000);
            assertTrue(trip.arrived(), "迷宫种子 " + seed + " 没到:" + trip);
        }
    }

    private static boolean connected(Grid grid) {
        boolean[] seen = new boolean[grid.w * grid.h];
        List<Integer> todo = new ArrayList<>(List.of(grid.start));
        seen[grid.start] = true;
        while (!todo.isEmpty()) {
            int s = todo.remove(todo.size() - 1);
            for (int n : grid.next(s)) {
                if (!seen[n]) {
                    seen[n] = true;
                    todo.add(n);
                }
            }
        }
        return seen[grid.goal];
    }
}
