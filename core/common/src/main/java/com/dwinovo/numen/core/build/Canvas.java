package com.dwinovo.numen.core.build;

import com.dwinovo.numen.core.task.build.BuildTaskRecord;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 原语一步一步画上去的那张图:每一格最终要成什么。后写覆盖先写——同一格写两次,留下后一笔,这是"一串原语"的语义。
 *
 * <p>没写过的格读<b>底子</b>({@link Ground})。{@code copy} 抄的就是"前面几步画完之后,这片地方是什么样":画过的读画上去的,
 * 没画过的读底子。底子是什么由坐标系决定:
 * <ul>
 *   <li>当场执行的原语写的是世界坐标,底子就是世界——{@code copy} 抄的是世界里已经立着的那一片;</li>
 *   <li>设计的坐标相对原点,不在任何地方,底子什么都没有——{@code copy} 只抄这份设计自己前面画的,设计因此和摆到哪儿无关,
 *       摆到哪儿都是同一栋。</li>
 * </ul>
 */
public final class Canvas {

    /** 没写过的格读什么。 */
    public interface Ground {

        /** 设计的底子:哪儿都是空的。 */
        Ground NOTHING = new Ground() {
            @Override
            public BlockState state(BlockPos pos) {
                return Blocks.AIR.defaultBlockState();
            }

            @Override
            public Item item(BlockState state, BlockPos at) {
                return BuildStates.materialItem(state.getBlock());
            }
        };

        /** 这一格立着什么。 */
        BlockState state(BlockPos pos);

        /** 抄到 {@code at} 的这一格该按哪件物品记账(见 {@link BuildStates#materialItem(BlockState, LevelReader, BlockPos)})。 */
        Item item(BlockState state, BlockPos at);

        /** 世界本身:当场执行的原语的底子。 */
        static Ground of(LevelReader level) {
            return new Ground() {
                @Override
                public BlockState state(BlockPos pos) {
                    return level.getBlockState(pos);
                }

                @Override
                public Item item(BlockState state, BlockPos at) {
                    return BuildStates.materialItem(state, level, at);
                }
            };
        }
    }

    private final Ground ground;
    private final Map<Long, BuildTaskRecord.Target> cells = new LinkedHashMap<>();
    /** 每一步各自画了哪些格(后来被覆盖的也算在那一步里):{@code build show} 逐步报。 */
    private final List<List<BuildTaskRecord.Target>> steps = new ArrayList<>();
    private int dropped;

    public Canvas(Ground ground) {
        this.ground = ground;
    }

    /** 开始画下一步。 */
    public void nextStep() {
        steps.add(new ArrayList<>());
    }

    /** 画一格:这一格原来画过的被盖掉。 */
    public void put(BuildTaskRecord.Target target) {
        if (steps.isEmpty()) {
            nextStep();
        }
        steps.get(steps.size() - 1).add(target);
        cells.remove(target.pos().asLong());
        cells.put(target.pos().asLong(), target);
        if (cells.size() > BuildShapes.MAX_TOTAL_CELLS) {
            throw new IllegalArgumentException("this comes to more than " + BuildShapes.MAX_TOTAL_CELLS
                    + " cells; build it as several pieces");
        }
    }

    /** 这一格前面几步画成了什么;没画过是 null(去读底子)。 */
    public BuildTaskRecord.Target drawn(BlockPos pos) {
        return cells.get(pos.asLong());
    }

    public Ground ground() {
        return ground;
    }

    /** 记下几格画不出来(液体、推不出记账物品的方块):掉格要有账。 */
    public void dropped(int count) {
        dropped += count;
    }

    /** 画完的每一格,按最后一次画它的先后。 */
    public List<BuildTaskRecord.Target> targets() {
        return List.copyOf(cells.values());
    }

    public List<List<BuildTaskRecord.Target>> steps() {
        return steps;
    }

    public int dropped() {
        return dropped;
    }

    /** 摆到世界里:每一格按 {@code at} 变换位置与朝向。 */
    public Layout laid(Placement at) {
        List<BuildTaskRecord.Target> out = new ArrayList<>(cells.size());
        for (BuildTaskRecord.Target t : cells.values()) {
            out.add(t.placed(at.cell(t.pos()), at.turn(t.desiredState())));
        }
        return Layout.of(out, dropped);
    }
}
