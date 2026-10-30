package com.dwinovo.numen.pathing.benchmark;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;

import com.dwinovo.numen.pathing.Vanilla;
import com.dwinovo.numen.pathing.plan.Stance;
import com.dwinovo.numen.pathing.search.SearchView;
import com.google.gson.JsonObject;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 出题:在每块地上用固定随机种子撒点,写成题库(见 {@link Dataset})。用法:{@code Generate <数据集目录> <主仓库提交>}。每块地、每个距离档收
 * {@link #QUOTA} 道题。题只由地形与种子定出,不依赖任何搜索算法,也不需要标准答案({@link Answers} 另算)。
 *
 * <p><b>怎么撒</b>:先随机一个起点(七成地表,三成洞穴地面;下界没有地表,什么站得住的格都算),再按档的距离范围随机一个方向与水平距离定出终点所在的列,
 * 在那一列上按终点的种类({@link Problem.EndClass})取一格:
 * <ul>
 *   <li>地表:这一列最高的站得住的格(含水面);洞穴:地表以下站得住、连着大片空间的地面;</li>
 *   <li>地下空腔:站得住、周围一团空气和流体不连着别处(六邻接泛洪到 {@link #POCKET} 格以内就到头)的地面;</li>
 *   <li>封闭的一格:它和六个邻居都是实心方块。</li>
 * </ul>
 * 目标种类照模型实际会下的令:走到某格、走到某格附近 r 格内(跟随,r 取 2–5)、挖到某格(只对封闭的一格)。
 * 起终点都离区域边缘至少 {@link #MARGIN} 格,搜索的快照读不到区域外。
 *
 * <p><b>平凡题</b>:起终点之间的直线明摆着一路平走过去(没有水、没有高于一格的台阶、没有无地面的列,见 {@link Straight})的不进题库。
 * 判据只看地形,与搜索算法无关。直线上的事实同时给报表的标签。
 *
 * <p><b>不适用于 A</b>:规格 A 不挖不垫,需要编辑才做得成的题(挖到某格、走到封死的一格)按构造不适用({@link Problem#applicable}),出题时分出来,
 * 数目记进 manifest,评测不给 A 跑。
 */
final class Generate {

    /** 每块地、每个距离档收几道题。 */
    static final int QUOTA = 40;
    /** 起终点离区域边缘至少这么多格(4 个区块)。 */
    static final int MARGIN = 64;
    /** 空腔泛洪最多数这么多格:超过就算连着别处(洞穴)。 */
    static final int POCKET = 4000;
    /** 每个档最多试这么多个候选,试不满就少收,不凑数。 */
    private static final int MAX_CANDIDATES = 20_000;
    private static final long SEED = 0x4E554D454E504233L;

    private Generate() {}

    public static void main(String[] args) {
        Vanilla.boot();
        Dataset dataset = new Dataset(Path.of(args[0]));
        long t0 = System.nanoTime();
        JsonObject manifest = new JsonObject();
        manifest.addProperty("dataset", "numen-pathfinding-benchmark");
        manifest.addProperty("minecraft", net.minecraft.SharedConstants.getCurrentVersion().getName());
        manifest.addProperty("commit", args[1]);
        manifest.addProperty("quota", QUOTA);
        manifest.addProperty("notApplicableToA", "需要编辑才做得成的题:挖到某格,和走到封死的空腔或封闭在实心里的一格;规格 A 不挖不垫,这些题不给 A 跑");
        JsonObject regionStats = new JsonObject();
        for (Dataset.Region region : dataset.regions()) {
            long r0 = System.nanoTime();
            RegionWorld world = RegionWorld.load(region.name(), dataset.save(region.name()));
            Regional gen = new Regional(world);
            List<Problem> problems = gen.generate(region.heldOnly());
            dataset.writeProblems(region.name(), problems);
            JsonObject stats = gen.stats(problems);
            stats.addProperty("seconds", Math.round((System.nanoTime() - r0) / 1e8) / 10.0);
            regionStats.add(region.name(), stats);
            System.out.println("[generate] " + region.name() + " " + stats);
        }
        manifest.add("regions", regionStats);
        manifest.addProperty("generateSeconds", Math.round((System.nanoTime() - t0) / 1e8) / 10.0);
        dataset.writeManifest(manifest);
    }

    /** 一块地上的出题。 */
    private static final class Regional {

        private final RegionWorld world;
        private final SearchView whole;
        private int candidates;
        private int plain;

        Regional(RegionWorld world) {
            this.world = world;
            this.whole = world.whole();
        }

        List<Problem> generate(boolean heldOnly) {
            List<Problem> out = new ArrayList<>();
            for (Problem.Bucket bucket : Problem.Bucket.values()) {
                SplittableRandom rng = new SplittableRandom(SEED ^ (world.name.hashCode() * 31L + bucket.ordinal()));
                int accepted = 0;
                for (int tried = 0; tried < MAX_CANDIDATES && accepted < QUOTA; tried++) {
                    Candidate c = draw(rng, bucket);
                    if (c == null) {
                        continue;
                    }
                    candidates++;
                    Straight.Facts facts = Straight.along(whole, c.from, c.to);
                    if (facts.plainWalk()) {
                        plain++;
                        continue;
                    }
                    String id = world.name + "-" + bucket.label + "-" + String.format("%03d", ++accepted);
                    out.add(new Problem(id, world.name, bucket, c.distance(), c.from, c.type, c.to, c.radius, c.endClass,
                            heldOnly ? Problem.Split.HELD : Problem.split(id), facts.tags(c.endClass)));
                }
                if (accepted < QUOTA) {
                    System.out.println("[generate] " + world.name + " " + bucket.label + " 只收到 " + accepted + " 道");
                }
            }
            return out;
        }

        JsonObject stats(List<Problem> problems) {
            JsonObject o = new JsonObject();
            o.addProperty("problems", problems.size());
            o.addProperty("candidates", candidates);
            o.addProperty("plainWalk", plain);
            Map<Problem.EndClass, Integer> byClass = new EnumMap<>(Problem.EndClass.class);
            Map<Problem.Split, Integer> bySplit = new EnumMap<>(Problem.Split.class);
            Map<Problem.GoalType, Integer> byGoal = new EnumMap<>(Problem.GoalType.class);
            Map<Problem.Bucket, Integer> byBucket = new EnumMap<>(Problem.Bucket.class);
            int notApplicable = 0;
            for (Problem p : problems) {
                byClass.merge(p.endClass(), 1, Integer::sum);
                bySplit.merge(p.split(), 1, Integer::sum);
                byGoal.merge(p.goalType(), 1, Integer::sum);
                byBucket.merge(p.bucket(), 1, Integer::sum);
                notApplicable += p.applicable(Spec.A) ? 0 : 1;
            }
            o.addProperty("notApplicableToA", notApplicable);
            o.add("byBucket", count(byBucket));
            o.add("byEndClass", count(byClass));
            o.add("byGoal", count(byGoal));
            o.add("bySplit", count(bySplit));
            return o;
        }

        private static <E extends Enum<E>> JsonObject count(Map<E, Integer> counts) {
            JsonObject o = new JsonObject();
            counts.forEach((k, v) -> o.addProperty(k.name().toLowerCase(), v));
            return o;
        }

        // ==================== 撒点 ====================

        private record Candidate(BlockPos from, BlockPos to, Problem.GoalType type, int radius, Problem.EndClass endClass) {

            /** 三维直线距离:分档的依据。 */
            double distance() {
                double dx = to.getX() - from.getX();
                double dy = to.getY() - from.getY();
                double dz = to.getZ() - from.getZ();
                return Math.sqrt(dx * dx + dy * dy + dz * dz);
            }
        }

        private Candidate draw(SplittableRandom rng, Problem.Bucket bucket) {
            BlockPos from = startCell(rng);
            if (from == null) {
                return null;
            }
            double low = Math.max(bucket.min, 6);
            double distance = low + rng.nextDouble() * (bucket.max - low);
            double angle = rng.nextDouble() * 2 * Math.PI;
            int x = from.getX() + (int) Math.round(Math.cos(angle) * distance);
            int z = from.getZ() + (int) Math.round(Math.sin(angle) * distance);
            if (x < world.minX() + MARGIN || x > world.maxX() - MARGIN || z < world.minZ() + MARGIN || z > world.maxZ() - MARGIN) {
                return null;
            }
            Problem.EndClass endClass = endClass(rng);
            BlockPos to = endCell(rng, endClass, x, z);
            if (to == null) {
                return null;
            }
            Candidate c = switch (endClass) {
                case BURIED -> new Candidate(from, to, rng.nextInt(100) < 40 ? Problem.GoalType.AT : Problem.GoalType.DIG, 0, endClass);
                default -> rng.nextInt(100) < 55 ? new Candidate(from, to, Problem.GoalType.AT, 0, endClass)
                        : new Candidate(from, to, Problem.GoalType.NEAR, 2 + rng.nextInt(4), endClass);
            };
            return Problem.Bucket.of(c.distance()) == bucket ? c : null;
        }

        /** 终点的种类:地表四成半、洞穴两成半、空腔一成、封闭的一格两成。 */
        private Problem.EndClass endClass(SplittableRandom rng) {
            int roll = rng.nextInt(100);
            return roll < 45 ? Problem.EndClass.SURFACE : roll < 70 ? Problem.EndClass.CAVE : roll < 80 ? Problem.EndClass.CAVITY
                    : Problem.EndClass.BURIED;
        }

        private BlockPos startCell(SplittableRandom rng) {
            int x = world.minX() + MARGIN + rng.nextInt(world.maxX() - world.minX() - 2 * MARGIN);
            int z = world.minZ() + MARGIN + rng.nextInt(world.maxZ() - world.minZ() - 2 * MARGIN);
            return rng.nextInt(100) < 70 ? surface(x, z) : cave(rng, x, z);
        }

        private BlockPos endCell(SplittableRandom rng, Problem.EndClass endClass, int x, int z) {
            return switch (endClass) {
                case SURFACE -> surface(x, z);
                case CAVE -> cave(rng, x, z);
                case CAVITY -> cavity(rng, x, z);
                case BURIED -> buried(rng, x, z);
            };
        }

        private boolean standable(int x, int y, int z) {
            return Stance.at(whole, Spec.STATS, x, y, z) != null;
        }

        /** 这一列最高的站得住的格;没有为 null。 */
        private BlockPos surface(int x, int z) {
            for (int y = world.scanTop; y >= world.heights.getMinBuildHeight(); y--) {
                if (standable(x, y, z)) {
                    return new BlockPos(x, y, z);
                }
            }
            return null;
        }

        /** 地表以下站得住的地面(脚下是实心的格,不在水里),随机一格;没有为 null。 */
        private BlockPos floor(SplittableRandom rng, int x, int z) {
            BlockPos top = surface(x, z);
            if (top == null) {
                return null;
            }
            List<BlockPos> cells = new ArrayList<>();
            for (int y = top.getY() - 5; y >= world.heights.getMinBuildHeight(); y--) {
                Stance stance = Stance.at(whole, Spec.STATS, x, y, z);
                if (stance != null && stance.kind() == Stance.Kind.GROUND) {
                    cells.add(new BlockPos(x, y, z));
                }
            }
            return cells.isEmpty() ? null : cells.get(rng.nextInt(cells.size()));
        }

        /** 洞穴:地表以下站得住、连着大片空间(不是封闭的空腔)的地面;没有为 null。 */
        private BlockPos cave(SplittableRandom rng, int x, int z) {
            BlockPos c = floor(rng, x, z);
            return c == null || closed(c) ? null : c;
        }

        /** 地下空腔:地表以下站得住、四周一团空气与流体不大于 {@link #POCKET} 格(不连着别处)的地面;没有为 null。 */
        private BlockPos cavity(SplittableRandom rng, int x, int z) {
            BlockPos c = floor(rng, x, z);
            return c == null || !closed(c) ? null : c;
        }

        /** 从 {@code c} 起六邻接的空气与流体连成的一团不大于 {@link #POCKET} 格,也没有碰到没加载的区块:封闭的。 */
        private boolean closed(BlockPos c) {
            ArrayDeque<BlockPos> queue = new ArrayDeque<>();
            HashSet<BlockPos> seen = new HashSet<>();
            queue.add(c);
            seen.add(c);
            while (!queue.isEmpty()) {
                BlockPos at = queue.poll();
                for (Direction d : Direction.values()) {
                    BlockPos next = at.relative(d);
                    if (seen.contains(next)) {
                        continue;
                    }
                    if (!whole.isLoaded(next.getX(), next.getZ())) {
                        return false;
                    }
                    BlockState state = whole.getBlockState(next);
                    if (state.isAir() || !state.getFluidState().isEmpty()) {
                        if (seen.size() >= POCKET) {
                            return false;
                        }
                        seen.add(next);
                        queue.add(next);
                    }
                }
            }
            return true;
        }

        /** 它和六个邻居都是实心方块的一格;没有为 null。 */
        private BlockPos buried(SplittableRandom rng, int x, int z) {
            BlockPos top = surface(x, z);
            if (top == null) {
                return null;
            }
            int low = world.heights.getMinBuildHeight() + 6;
            int high = top.getY() - 8;
            if (high <= low) {
                return null;
            }
            BlockPos c = new BlockPos(x, low + rng.nextInt(high - low), z);
            if (!solid(c)) {
                return null;
            }
            for (Direction d : Direction.values()) {
                if (!solid(c.relative(d))) {
                    return null;
                }
            }
            return c;
        }

        private boolean solid(BlockPos pos) {
            BlockState state = whole.getBlockState(pos);
            return state.isSolid() && state.getFluidState().isEmpty();
        }
    }
}
