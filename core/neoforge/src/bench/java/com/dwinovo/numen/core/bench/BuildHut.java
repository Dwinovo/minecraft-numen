package com.dwinovo.numen.core.bench;

import com.dwinovo.numen.bench.Check;
import com.dwinovo.numen.bench.Scenario;
import com.dwinovo.numen.bench.Scene;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
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
 * 盖一个能住的小屋:一块空地,她包里两组橡木板和一扇橡木门。主人只说在这盖个能住的小屋。
 *
 * <h2>怎么算"能住"</h2>
 * 场地里有一扇门,门的一侧围起来、另一侧通到外面:
 * <ul>
 *   <li>从门两侧各自的那一格往外灌(六个方向,只走碰撞箱为空的格;门本身算墙);</li>
 *   <li>灌到场地边上一圈、灌到场地顶层(露天),或者灌满 {@link #FLOOD_LIMIT} 格还没停,这一侧就是"外面";</li>
 *   <li>另一侧停下来了就是"里面",里面至少要有 {@link #MIN_FLOOR} 格站得下的地方(脚下实心、这一格与头顶一格都空)。</li>
 * </ul>
 * 子目标看完成度:放了门、墙起来了(场地里木板不少于 {@link #MIN_WALL_PLANKS} 块)、有顶(悬空、底下是空的木板在三格以上
 * 至少 {@link #MIN_ROOF_PLANKS} 块)。
 */
public final class BuildHut implements Scenario {

    private static final int FLOOD_LIMIT = 2000;
    private static final int MIN_FLOOR = 4;
    private static final int MIN_WALL_PLANKS = 24;
    private static final int MIN_ROOF_PLANKS = 4;
    /** 标准解那栋屋子的西北角(外沿),五乘五。 */
    private static final BlockPos CORNER = new BlockPos(8, 1, 8);

    @Override
    public String id() {
        return "build_hut";
    }

    @Override
    public BlockPos start() {
        return new BlockPos(5, 1, 5);
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
                Check.subgoal("墙起来了", s -> s.assertTrue(planks(s, false) >= MIN_WALL_PLANKS,
                        "场地里只有 " + planks(s, false) + " 块木板")),
                Check.subgoal("有顶", s -> s.assertTrue(planks(s, true) >= MIN_ROOF_PLANKS,
                        "悬空的木板只有 " + planks(s, true) + " 块")));
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
            Room front = flood(scene, door.relative(facing));
            Room back = flood(scene, door.relative(facing.getOpposite()));
            Room inside = front.outside ? back : front;
            Room outside = front.outside ? front : back;
            if (!outside.outside) {
                why = "门 " + door.toShortString() + " 两侧都是封死的";
            } else if (inside.outside) {
                why = "门 " + door.toShortString() + " 两侧都通到外面,没有围起来";
            } else if (inside.floor < MIN_FLOOR) {
                why = "门 " + door.toShortString() + " 里面只有 " + inside.floor + " 格站得下";
            } else {
                return null;
            }
        }
        return why;
    }

    /** 一侧灌出来的样子:通没通到外面、里面站得下几格。 */
    private record Room(boolean outside, int floor) {}

    private Room flood(Scene scene, BlockPos from) {
        int size = arena().size();
        int top = arena().height();
        if (!open(scene, from)) {
            return new Room(false, 0);
        }
        Set<BlockPos> seen = new HashSet<>();
        Deque<BlockPos> todo = new ArrayDeque<>();
        seen.add(from);
        todo.add(from);
        int floor = 0;
        while (!todo.isEmpty()) {
            BlockPos p = todo.poll();
            if (p.getX() <= 0 || p.getZ() <= 0 || p.getX() >= size - 1 || p.getZ() >= size - 1 || p.getY() >= top
                    || seen.size() > FLOOD_LIMIT) {
                return new Room(true, floor);
            }
            if (!open(scene, p.below()) && open(scene, p.above())) {
                floor++;
            }
            for (Direction d : Direction.values()) {
                BlockPos n = p.relative(d);
                if (n.getY() >= 1 && open(scene, n) && seen.add(n)) {
                    todo.add(n);
                }
            }
        }
        return new Room(false, floor);
    }

    /** 人走得过的格:碰撞箱为空;门算墙。坐标相对场地。 */
    private static boolean open(Scene scene, BlockPos rel) {
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

    /** 场地里的木板;{@code roof} 只数三格以上、底下是空的那些。 */
    private int planks(Scene scene, boolean roof) {
        int n = 0;
        int size = arena().size();
        for (int x = 0; x < size; x++) {
            for (int z = 0; z < size; z++) {
                for (int y = roof ? 3 : 1; y <= arena().height(); y++) {
                    BlockPos rel = new BlockPos(x, y, z);
                    if (scene.level().getBlockState(scene.pos(rel)).is(BlockTags.PLANKS)
                            && (!roof || open(scene, rel.below()))) {
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
    public List<String> solution(Scene scene) {
        BlockPos c = scene.pos(CORNER);
        String x = String.valueOf(c.getX());
        String z = String.valueOf(c.getZ());
        int y = c.getY();
        BlockPos door = scene.pos(CORNER.offset(2, 0, 4));
        return List.of(
                "build layer " + x + " " + y + " " + z + " ##### #...# #...# #...# ##.## --block oak_planks --up_to "
                        + (y + 1),
                "build layer " + x + " " + (y + 2) + " " + z + " ##### #...# #...# #...# ##### --block oak_planks",
                "build layer " + x + " " + (y + 3) + " " + z + " ##### ##### ##### ##### ##### --block oak_planks",
                "build set oak_door[facing=south] " + door.getX() + " " + door.getY() + " " + door.getZ());
    }
}
