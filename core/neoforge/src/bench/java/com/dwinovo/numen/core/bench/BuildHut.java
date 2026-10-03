package com.dwinovo.numen.core.bench;

import com.dwinovo.numen.cli.Shapes;
import com.dwinovo.numen.bench.Check;
import com.dwinovo.numen.bench.Scenario;
import com.dwinovo.numen.bench.Scene;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 盖一个能住的小屋:一块空地,她站在正中(四面各有十格,就地盖不会撞上场地边的屏障),包里两组橡木板和一扇橡木门。
 * 主人只说在这盖个能住的小屋。
 *
 * <h2>怎么算"能住"</h2>
 * 场地里有一扇门,门里侧的人不开门走不出去、头顶有遮挡,门外侧走得到外面:
 * <ul>
 *   <li>从门两侧各自脚下的那一格起,按人走的样子往外走:站得住的格(脚下实心、这一格与头顶一格都空)之间平走、上一格台阶、
 *       往下落至多三格;门本身算墙。窗洞、一格高的缝人钻不出去,不算漏;</li>
 *   <li>走到场地边上一圈,或者走满 {@link #WALK_LIMIT} 格还没停,这一侧就是"外面";</li>
 *   <li>另一侧停下来了就是"里面":至少 {@link #MIN_FLOOR} 格站得住,每一格头顶 {@link #ROOF_REACH} 格以内都有遮挡。</li>
 * </ul>
 * 用什么方块盖都算。子目标看完成度:放了门、墙起来了(地面以上的方块不少于 {@link #MIN_WALL_BLOCKS} 块)、有顶(三格以上、
 * 底下是空的方块至少 {@link #MIN_ROOF_BLOCKS} 块)。场地本来是空的,地面以上的方块都是她放的。
 */
public final class BuildHut implements Scenario {

    private static final int WALK_LIMIT = 2000;
    private static final int MIN_FLOOR = 4;
    private static final int ROOF_REACH = 4;
    private static final int MAX_DROP = 3;
    private static final int MIN_WALL_BLOCKS = 24;
    private static final int MIN_ROOF_BLOCKS = 4;
    /** 标准解那栋屋子的西北角(外沿),五乘五,在她东南边。 */
    private static final BlockPos CORNER = new BlockPos(12, 1, 12);

    @Override
    public String id() {
        return "build_hut";
    }

    @Override
    public BlockPos start() {
        return new BlockPos(10, 1, 10);
    }

    @Override
    public void setup(Scene scene) {
        scene.give(new ItemStack(Items.OAK_PLANKS, 64));
        scene.give(new ItemStack(Items.OAK_PLANKS, 64));
        scene.give(new ItemStack(Items.OAK_DOOR));
    }

    @Override
    public String opening() {
        return "在这儿给我盖个能住的小屋吧。";
    }

    @Override
    public List<Check> checks() {
        return List.of(
                Check.success("有门、门里围起来能住人", s -> s.assertTrue(livable(s) == null, livable(s))),
                Check.subgoal("放了门", s -> s.assertTrue(!doors(s).isEmpty(), "场地里没有门")),
                Check.subgoal("墙起来了", s -> s.assertTrue(placed(s, false) >= MIN_WALL_BLOCKS,
                        "地面以上只有 " + placed(s, false) + " 块")),
                Check.subgoal("有顶", s -> s.assertTrue(placed(s, true) >= MIN_ROOF_BLOCKS,
                        "悬空的方块只有 " + placed(s, true) + " 块")));
    }

    /** 能住就是 null;不能住说为什么。 */
    private String livable(Scene scene) {
        List<BlockPos> doors = doors(scene);
        if (doors.isEmpty()) {
            return "场地里没有门";
        }
        String why = null;
        for (BlockPos door : doors) {
            Direction facing = scene.level().getBlockState(scene.pos(door)).getValue(DoorBlock.FACING);
            Room front = walk(scene, door.relative(facing));
            Room back = walk(scene, door.relative(facing.getOpposite()));
            Room inside = front.outside ? back : front;
            Room outside = front.outside ? front : back;
            if (!outside.outside) {
                why = "门 " + door.toShortString() + " 两侧都走不出去";
            } else if (inside.outside) {
                why = "门 " + door.toShortString() + " 两侧都走得到外面,没有围起来";
            } else if (inside.floor < MIN_FLOOR) {
                why = "门 " + door.toShortString() + " 里面只有 " + inside.floor + " 格站得住";
            } else if (inside.open > 0) {
                why = "门 " + door.toShortString() + " 里面有 " + inside.open + " 格头顶 " + ROOF_REACH + " 格内没有遮挡";
            } else {
                return null;
            }
        }
        return why;
    }

    /** 一侧走出来的样子:走没走到外面、站得住几格、其中头顶露天的几格。 */
    private record Room(boolean outside, int floor, int open) {}

    private Room walk(Scene scene, BlockPos beside) {
        int size = arena().size();
        BlockPos from = land(scene, beside);
        if (from == null) {
            return new Room(false, 0, 0);
        }
        Set<BlockPos> seen = new HashSet<>();
        Deque<BlockPos> todo = new ArrayDeque<>();
        seen.add(from);
        todo.add(from);
        int open = 0;
        while (!todo.isEmpty()) {
            BlockPos p = todo.poll();
            if (p.getX() <= 0 || p.getZ() <= 0 || p.getX() >= size - 1 || p.getZ() >= size - 1
                    || seen.size() > WALK_LIMIT) {
                return new Room(true, seen.size(), open);
            }
            if (!roofed(scene, p)) {
                open++;
            }
            for (Direction d : Direction.Plane.HORIZONTAL) {
                BlockPos n = p.relative(d);
                BlockPos step = standable(scene, n.above()) && clear(scene, p.above(2)) ? n.above() : land(scene, n);
                if (step != null && seen.add(step)) {
                    todo.add(step);
                }
            }
        }
        return new Room(false, seen.size(), open);
    }

    /** 从这一格往下落,落到的站得住的那一格;落不到(实心、或者落差超过 {@link #MAX_DROP})是 null。 */
    private BlockPos land(Scene scene, BlockPos rel) {
        for (int drop = 0; drop <= MAX_DROP; drop++) {
            BlockPos at = rel.below(drop);
            if (!clear(scene, at)) {
                return null;
            }
            if (standable(scene, at)) {
                return at;
            }
        }
        return null;
    }

    /** 人站得住:脚下实心,这一格与头顶一格都空。 */
    private static boolean standable(Scene scene, BlockPos rel) {
        return clear(scene, rel) && clear(scene, rel.above()) && !clear(scene, rel.below());
    }

    /** 头顶 {@link #ROOF_REACH} 格以内有遮挡。 */
    private static boolean roofed(Scene scene, BlockPos rel) {
        for (int up = 2; up <= ROOF_REACH + 1; up++) {
            if (!clear(scene, rel.above(up))) {
                return true;
            }
        }
        return false;
    }

    /** 人过得去的格:碰撞箱为空;门算墙。坐标相对场地。 */
    private static boolean clear(Scene scene, BlockPos rel) {
        BlockPos at = scene.pos(rel);
        BlockState state = scene.level().getBlockState(at);
        return !(state.getBlock() instanceof DoorBlock) && state.getCollisionShape(scene.level(), at).isEmpty();
    }

    /** 场地里每扇门的下半(相对坐标)。 */
    private List<BlockPos> doors(Scene scene) {
        List<BlockPos> out = new ArrayList<>();
        int size = arena().size();
        for (int x = 0; x < size; x++) {
            for (int z = 0; z < size; z++) {
                for (int y = 1; y < arena().height(); y++) {
                    BlockState state = scene.level().getBlockState(scene.pos(x, y, z));
                    if (state.getBlock() instanceof DoorBlock
                            && state.getValue(DoorBlock.HALF) == DoubleBlockHalf.LOWER) {
                        out.add(new BlockPos(x, y, z));
                    }
                }
            }
        }
        return out;
    }

    /** 地面以上的方块(场地本来是空的,都是她放的);{@code roof} 只数三格以上、底下是空的那些。 */
    private int placed(Scene scene, boolean roof) {
        int n = 0;
        int size = arena().size();
        for (int x = 0; x < size; x++) {
            for (int z = 0; z < size; z++) {
                for (int y = roof ? 3 : 1; y <= arena().height(); y++) {
                    BlockPos rel = new BlockPos(x, y, z);
                    if (!scene.level().getBlockState(scene.pos(rel)).isAir() && (!roof || clear(scene, rel.below()))) {
                        n++;
                    }
                }
            }
        }
        return n;
    }

    /**
     * 五乘五的木屋:一、二层是留了门洞的一圈墙,第三层整圈,第四层整片屋顶,门洞里放门。71 块木板。
     */
    @Override
    public String solution(Scene scene) {
        BlockPos c = scene.pos(CORNER);
        int x = c.getX();
        int z = c.getZ();
        int y = c.getY();
        BlockPos door = scene.pos(CORNER.offset(2, 0, 4));
        // 原语只放手够得着的格:先站进屋子正中,四面墙和屋顶都在手边
        BlockPos middle = scene.pos(CORNER.offset(2, 0, 2));
        return "move.to(" + Shapes.literal(middle) + ")\n"
                + "build.layer({\"#####\", \"#...#\", \"#...#\", \"#...#\", \"##.##\"}, {at = "
                + Shapes.literal(new BlockPos(x, y, z)) + ", block = \"oak_planks\", up_to = " + (y + 1) + "})\n"
                + "build.layer({\"#####\", \"#...#\", \"#...#\", \"#...#\", \"#####\"}, {at = "
                + Shapes.literal(new BlockPos(x, y + 2, z)) + ", block = \"oak_planks\"})\n"
                + "build.layer({\"#####\", \"#####\", \"#####\", \"#####\", \"#####\"}, {at = "
                + Shapes.literal(new BlockPos(x, y + 3, z)) + ", block = \"oak_planks\"})\n"
                + "build.set(" + Shapes.literal(door) + ", {block = \"oak_door[facing=south]\"})";
    }
}
