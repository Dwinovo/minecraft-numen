package com.dwinovo.numen.pathing.body;

import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

/**
 * 端口:真的动手——左键挖、右键用(放方块、开关门)。执行层只经它改世界,并把它如实交回的结果记进实际账:挖掉的是
 * 哪一格、原来是什么;右键之后哪几格变成了什么;没成就说为什么。
 *
 * <p>一具服务端假玩家怎样挖、怎样放,原版机制只有一份,就是 {@link PlayerHands}。宿主在它外面组合自己的规矩——
 * 每一下之前问许可、被拒就交回自己的理由——而不另写一套挖法。模块自己不判许可。
 *
 * <p>只在世界所在的线程上调用。
 */
public interface Effector {

    /**
     * 按住左键,这一刻挖准星落着的那一格({@code hit} 就是准星的拾取结果)。换了一格就从头挖;挖到那一格碎掉为止要按
     * 住好几刻,中间松开({@link #release})进度清零,与真玩家一样。
     */
    Strike dig(BlockHitResult hit);

    /** 松开左键:正在挖的那一格放下,进度清零。没在挖时什么也不做。 */
    void release();

    /** 右键点一下准星落着的那一格({@code hit} 就是准星的拾取结果):主手先试,不成再用副手。 */
    Use use(BlockHitResult hit);

    /** 左键按住这一刻的结果。 */
    sealed interface Strike {

        /** 还在挖,或上一格刚碎、手还没缓过来。 */
        record Swinging() implements Strike {}

        /** 碎了:{@code pos} 这一格原来是 {@code before}。 */
        record Broke(BlockPos pos, BlockState before) implements Strike {}

        /** 没让挖:{@code reason} 是拒绝方自己的理由,模块不解读。 */
        record Refused(BlockPos pos, Object reason) implements Strike {}

        Strike SWINGING = new Swinging();
    }

    /** 右键这一下的结果。 */
    sealed interface Use {

        /** 手还没缓过来(原版两次右键至少隔 4 刻),这一下没按。 */
        record Waiting() implements Use {}

        /** 按了,世界里这几格变了。 */
        record Changed(List<Change> changes) implements Use {

            public Changed {
                changes = List.copyOf(changes);
            }
        }

        /** 按了,什么也没变。 */
        record Nothing() implements Use {}

        /** 没让按:{@code reason} 是拒绝方自己的理由,模块不解读。 */
        record Refused(BlockPos pos, Object reason) implements Use {}

        Use WAITING = new Waiting();
        Use NOTHING = new Nothing();
    }

    /** 一格从 {@code before} 变成了 {@code after}。 */
    record Change(BlockPos pos, BlockState before, BlockState after) {}
}
