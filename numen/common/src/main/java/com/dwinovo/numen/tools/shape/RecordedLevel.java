package com.dwinovo.numen.tools.shape;

import com.dwinovo.numen.api.sdk.BlockAt;
import com.dwinovo.numen.build.BuildStates;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.util.RandomSource;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * 记录用的世界:原版的地物、树、结构生成器要一个 {@link WorldGenLevel} 来读和写,这里给它一个替身(代理)。
 *
 * <p>读:方块照实读真实世界,只读已加载的区块({@link LoadedBlocks},全仓读世界的唯一一处),读到没加载的整个调用失败;但读到的是
 * <b>覆盖层叠在真实世界上</b>的样子,所以同一次生成里先写的后面读得到。写:一律只记进覆盖层,真实世界从头到尾不动——不触发方块更新、
 * 不生成实体、不碰光照与高度图缓存,什么也不留在磁盘上。生成器要的随机数、高度图也在替身里答(不用真实世界的随机数发生器、不读它的高度图缓存)。
 * 凡是返回 void 的方法一律吞掉(计划刻、方块更新、音效、游戏事件……真实世界的这些队列一个也不进)。
 *
 * <p>生成器加的实体、造出的方块实体(箱子、刷怪笼、告示牌……)带的数据不进 Cells(Cells 只有方块状态),
 * 在 {@link #lost} 里如实记下是什么、在哪。方块实体在覆盖层里真的造出来(战利品表、刷怪笼的怪物之类的写入才有地方落),但只为让生成器写完。
 */
final class RecordedLevel implements InvocationHandler {

    /** 生成器想要、但 Cells 装不下的东西:一个实体,或一个方块实体的数据。 */
    record Lost(String kind, String name, BlockPos pos) {

        static final String ENTITY = "entity";
        static final String BLOCK_ENTITY = "block_entity";
    }

    private final LoadedBlocks real;
    private final RandomSource random = RandomSource.create();
    private final Map<BlockPos, BlockState> written = new LinkedHashMap<>();
    private final Map<BlockPos, BlockEntity> blockEntities = new HashMap<>();
    private final List<Lost> entities = new ArrayList<>();
    /** 覆盖层的改动记录,一次尝试失败时按它退回去。 */
    private final List<Undo> journal = new ArrayList<>();
    private final WorldGenLevel view;

    private record Undo(BlockPos pos, BlockState before, BlockEntity blockEntityBefore) {}

    RecordedLevel(LoadedBlocks real) {
        this.real = real;
        this.view = (WorldGenLevel) Proxy.newProxyInstance(WorldGenLevel.class.getClassLoader(),
                new Class<?>[]{WorldGenLevel.class}, this);
    }

    /** 交给生成器的世界。 */
    WorldGenLevel level() {
        return view;
    }

    RandomSource random() {
        return random;
    }

    LoadedBlocks real() {
        return real;
    }

    /** 这一格此刻的方块:覆盖层里有就是它,没有就是真实世界的。 */
    BlockState at(BlockPos pos) {
        BlockState set = written.get(pos);
        return set != null ? set : real.at(pos);
    }

    /** 一次尝试:成功留着它写的,失败(返回 false 或抛出)退回到尝试前,它加的实体也不算。 */
    boolean attempt(java.util.function.BooleanSupplier body) {
        int mark = journal.size();
        int lostMark = entities.size();
        boolean placed;
        try {
            placed = body.getAsBoolean();
        } catch (RuntimeException e) {
            rollBack(mark, lostMark);
            throw e;
        }
        if (!placed) {
            rollBack(mark, lostMark);
        }
        return placed;
    }

    private void rollBack(int mark, int lostMark) {
        for (int i = journal.size() - 1; i >= mark; i--) {
            Undo undo = journal.remove(i);
            if (undo.before() == null) {
                written.remove(undo.pos());
            } else {
                written.put(undo.pos(), undo.before());
            }
            if (undo.blockEntityBefore() == null) {
                blockEntities.remove(undo.pos());
            } else {
                blockEntities.put(undo.pos(), undo.blockEntityBefore());
            }
        }
        while (entities.size() > lostMark) {
            entities.remove(entities.size() - 1);
        }
    }

    boolean set(BlockPos pos, BlockState state) {
        real.requireLoaded(pos);
        BlockPos key = pos.immutable();
        journal.add(new Undo(key, written.get(key), blockEntities.get(key)));
        written.put(key, state);
        blockEntities.remove(key);
        if (state.hasBlockEntity() && state.getBlock() instanceof EntityBlock holder) {
            BlockEntity made = holder.newBlockEntity(key, state);
            if (made != null) {
                blockEntities.put(key, made);
            }
        }
        return true;
    }

    /** 写进覆盖层、且和真实世界此刻不同的格,按写的先后。 */
    List<BlockAt> blocks() {
        List<BlockAt> out = new ArrayList<>();
        for (Map.Entry<BlockPos, BlockState> e : written.entrySet()) {
            if (real.at(e.getKey()) != e.getValue()) {
                out.add(new BlockAt(BuildStates.written(e.getValue()), e.getKey()));
            }
        }
        return out;
    }

    /** Cells 装不下的:加的实体、带数据的方块实体(只算最终进了 {@link #blocks} 的那些格)。 */
    List<Lost> lost() {
        List<Lost> out = new ArrayList<>(entities);
        for (Map.Entry<BlockPos, BlockEntity> e : blockEntities.entrySet()) {
            BlockState state = written.get(e.getKey());
            if (state != null && real.at(e.getKey()) != state) {
                out.add(new Lost(Lost.BLOCK_ENTITY, BuildStates.written(state), e.getKey()));
            }
        }
        return out;
    }

    private void drop(Entity entity) {
        entities.add(new Lost(Lost.ENTITY, net.minecraft.world.entity.EntityType.getKey(entity.getType()).toString(),
                entity.blockPosition()));
    }

    /**
     * 替身上的每一次调用:读写方块、方块实体、实体、随机数、区块、群系、高度图在这里答(按方法名和参数形状认,形状不符的照下面的默认走);
     * 其余返回 void 的吞掉,接口自带默认实现的走默认实现,剩下的转给真实世界。
     */
    @Override
    @SuppressWarnings("unchecked")
    public Object invoke(Object proxy, Method method, Object[] given) throws Throwable {
        Object[] args = given == null ? new Object[0] : given;
        if (method.getDeclaringClass() == Object.class) {
            return switch (method.getName()) {
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                default -> "recorded level";
            };
        }
        int n = args.length;
        boolean onePos = n == 1 && args[0] instanceof BlockPos;
        switch (method.getName()) {
            case "getBlockState" -> {
                if (onePos) {
                    return at((BlockPos) args[0]);
                }
            }
            case "getFluidState" -> {
                if (onePos) {
                    return at((BlockPos) args[0]).getFluidState();
                }
            }
            case "isStateAtPosition" -> {
                if (n == 2 && args[0] instanceof BlockPos pos && args[1] instanceof Predicate<?> test) {
                    return ((Predicate<BlockState>) test).test(at(pos));
                }
            }
            case "getBlockEntity" -> {
                if (onePos) {
                    return blockEntities.get((BlockPos) args[0]);
                }
            }
            case "setBlock" -> {
                if (n >= 2 && args[0] instanceof BlockPos pos && args[1] instanceof BlockState state) {
                    return set(pos, state);
                }
            }
            case "removeBlock", "destroyBlock" -> {
                if (n >= 2 && args[0] instanceof BlockPos pos) {
                    // 拿走一格留下它此刻的流体(含水的方块留下水),没有流体就是空气
                    return set(pos, at(pos).getFluidState().createLegacyBlock());
                }
            }
            case "addFreshEntity" -> {
                if (n == 1 && args[0] instanceof Entity entity) {
                    drop(entity);
                    return true;
                }
            }
            case "addFreshEntityWithPassengers" -> {
                if (n == 1 && args[0] instanceof Entity entity) {
                    entity.getSelfAndPassengers().forEach(this::drop);
                    return true;
                }
            }
            case "getRandom" -> {
                return random;
            }
            case "getChunk" -> {
                if (n >= 2 && args[0] instanceof Integer cx && args[1] instanceof Integer cz) {
                    return real.chunk(cx, cz);
                }
                if (onePos) {
                    BlockPos pos = (BlockPos) args[0];
                    return real.chunk(pos.getX() >> 4, pos.getZ() >> 4);
                }
            }
            case "getBiome" -> {
                if (onePos) {
                    return real.biome((BlockPos) args[0]);
                }
            }
            case "getNoiseBiome" -> {
                if (n == 3 && args[0] instanceof Integer x && args[1] instanceof Integer y && args[2] instanceof Integer z) {
                    return real.biome(new BlockPos(x * 4, y * 4, z * 4));
                }
            }
            case "getHeight" -> {
                if (n == 3 && args[0] instanceof Heightmap.Types type && args[1] instanceof Integer x
                        && args[2] instanceof Integer z) {
                    return height(type, x, z);
                }
            }
            default -> {
            }
        }
        if (method.getReturnType() == void.class) {
            return null;
        }
        if (method.isDefault()) {
            return InvocationHandler.invokeDefault(proxy, method, args);
        }
        try {
            return method.invoke(real.level(), args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    /** 高度图的高度:这一列最高的"挡"住的方块往上一格(一个也没有是世界最低),按覆盖层叠起来的样子算。 */
    private int height(Heightmap.Types type, int x, int z) {
        Predicate<BlockState> blocks = type.isOpaque();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos(x, 0, z);
        for (int y = real.maxY(); y >= real.minY(); y--) {
            if (blocks.test(at(cursor.setY(y)))) {
                return y + 1;
            }
        }
        return real.minY();
    }
}
