package com.dwinovo.numen.nav;

import java.util.List;

import com.dwinovo.numen.api.sdk.Doc;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/**
 * 路程回执:{@code numen.move.to}、{@code away}、{@code follow} 交回的值,也是它们失败时错误值的 {@code data}——停在哪、离要去的地方
 * 还差多远、路上挖了几格放了几格、问过什么、被拒了什么。没到的原因是错误的话,这里是它做到了哪。只有这一个类型:各处走路的活
 * 回执里说的就是这一份({@link Journey#walked}),路上的细账另在回执末尾的那一句里({@link NavText#journey})。
 */
@Doc("The account of a walk: where it ended and what it did on the way.")
public record Walked(@Doc("Where you stand now (decimals).") Vec3 pos,
                     @Doc("Blocks from where you were heading; 0 or so when there.") double distanceLeft,
                     @Doc("Blocks it broke on the way.") int dug,
                     @Doc("Blocks it placed on the way.") int placed,
                     @Doc("Every cell it broke on the way, in the order it broke them; the receipt text lists only the "
                             + "first few of each kind.") List<BlockPos> dugAt,
                     @Doc("Every cell it placed on the way (bridge, pillar and step blocks), in order; the receipt text "
                             + "lists only the first few of each kind. Break them again with numen.work.dig to clear the "
                             + "way behind you.") List<BlockPos> placedAt,
                     @Doc("What your owner or the rules ruled about the cells it wanted to change on the way: asked and "
                             + "allowed, asked and refused, refused outright.") List<Ruling> rulings) {

    public Walked {
        dugAt = List.copyOf(dugAt);
        placedAt = List.copyOf(placedAt);
        rulings = List.copyOf(rulings);
    }
}
