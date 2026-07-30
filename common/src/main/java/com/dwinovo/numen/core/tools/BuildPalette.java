package com.dwinovo.numen.core.tools;

import com.dwinovo.numen.agent.tool.ToolArgs;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import java.util.ArrayList;
import java.util.List;

/**
 * 加权调色板:一条指令的方块参数可以是一族方块,而不是单一方块。
 *
 * <p>一整面同色的墙是"一眼假"的头号来源。真实建筑的表面从来不是纯色——石墙里
 * 掺苔石与裂石,木墙里掺原木与去皮木。把混搭做进原语层,每一处平面自动带质感,
 * 不必让模型逐格去想,也不必把一面墙拆成十几条指令。
 *
 * <p>写法:{@code "stone_bricks*8, mossy_stone_bricks, cracked_stone_bricks"}。
 * 省略权重即为 1;只写一种就退化成从前的单方块行为。
 *
 * <p>取样按<b>位置哈希</b>,不用随机数发生器:同一格永远取到同一个方块。于是
 * 预览与施工一致、重跑一致、断点续建也一致——这三件事任缺其一,玩家看到的房子
 * 就会和确认过的那张不是同一栋。
 */
public final class BuildPalette {

    /** 单项:方块 + 对应物品 + 权重。 */
    public record Entry(Block block, Item item, String label, int weight) {}

    private final List<Entry> entries;
    private final int totalWeight;

    private BuildPalette(List<Entry> entries) {
        this.entries = List.copyOf(entries);
        int sum = 0;
        for (Entry e : entries) {
            sum += e.weight();
        }
        this.totalWeight = Math.max(1, sum);
    }

    /**
     * 解析方块参数。
     *
     * @param spec {@code "oak_planks"} 或 {@code "stone*8, mossy_cobblestone*2, cobblestone"}
     */
    public static BuildPalette parse(String spec) {
        if (spec == null || spec.isBlank()) {
            throw new IllegalArgumentException("block_id must not be empty");
        }
        List<Entry> entries = new ArrayList<>();
        for (String part : spec.split(",")) {
            String token = part.trim();
            if (token.isEmpty()) {
                continue;
            }
            int weight = 1;
            int star = token.lastIndexOf('*');
            if (star > 0) {
                String tail = token.substring(star + 1).trim();
                try {
                    weight = Math.max(1, Integer.parseInt(tail));
                    token = token.substring(0, star).trim();
                } catch (NumberFormatException ignored) {
                    // 不是权重后缀(方块名里本来就带星号的情形),整段当方块名
                }
            }
            entries.add(toEntry(token, weight));
        }
        if (entries.isEmpty()) {
            throw new IllegalArgumentException("block_id must name at least one block");
        }
        return new BuildPalette(entries);
    }

    private static Entry toEntry(String id, int weight) {
        return resolve(id, weight);
    }

    /**
     * 把一个 block_id 解析成方块 + 计费用的物品。<b>按方块注册表查,不按物品。</b>
     *
     * <p>此前这里走 {@code ToolArgs.parseItem}——那是背包工具的入口,它把 AIR 当
     * "未知物品"拒掉。这条守卫对吃/丢/取是对的(不能吃空气),对建造是错的:建造
     * 的世界是方块,而 {@code air} 恰恰是我们自己在工具描述里承诺过的"清空这一格"。
     * 后果是两处 {@code if (item == AIR)} 的分支全成了死代码,而模型照着文档写
     * {@code minecraft:air} 会收到一句 {@code unknown item: air}——名字明明是对的,
     * 它无从判断问题出在哪,只能换个写法反复重试。实测 60 次 build 调用被拒 22 次,
     * 其中 18 次就是 air 与 water。
     *
     * <p>能不能建这件事本身不在这里判——它和图纸入口共用
     * {@link com.dwinovo.numen.core.task.BuildStates#unbuildableReason};这边把它
     * 当拒绝理由抛出去,那边把它当跳过条件。两处各判各的迟早会分叉。
     */
    public static Entry resolve(String id, int weight) {
        String trimmed = id.trim();
        if (trimmed.indexOf('[') >= 0) {
            throw new IllegalArgumentException(trimmed
                    + " — block_id takes a plain block id; put the state in `properties`"
                    + " (e.g. block_id \"spruce_stairs\" with properties {facing: south})");
        }
        var rl = net.minecraft.resources.ResourceLocation.tryParse(trimmed);
        if (rl == null) {
            throw new IllegalArgumentException("not a valid block id: " + trimmed);
        }
        if (!net.minecraft.core.registries.BuiltInRegistries.BLOCK.containsKey(rl)) {
            throw new IllegalArgumentException("unknown block: " + trimmed);
        }
        Block block = net.minecraft.core.registries.BuiltInRegistries.BLOCK.get(rl);
        // 能不能建走同一个判据(图纸入口那边拿它当跳过条件,这边拿它当拒绝理由)
        String no = com.dwinovo.numen.core.task.BuildStates
                .unbuildableReason(block.defaultBlockState());
        if (no != null) {
            throw new IllegalArgumentException(trimmed + " — " + no);
        }
        Item item = substituteItem(block);
        if (item == Items.AIR && block != Blocks.AIR) {
            throw new IllegalArgumentException(trimmed + " is not a placeable block");
        }
        String label = trimmed.contains(":") ? trimmed.split(":", 2)[1] : trimmed;
        return new Entry(block, item, label, weight);
    }

    /**
     * 有些方块没有自己的物品,得拿别的料去做出来。
     *
     * <p>耕地和土径是用锄/锹在土上加工出来的,清单上算<b>土</b>;高草与大蕨类没有
     * 自己的方块物品,是两株矮的长成的,清单上算两株矮的(件数在
     * {@code Target#materialCount} 里)。
     *
     * <p>不列这张表的后果很实在:{@code dirt_path} 是我们自己在装饰指南里教模型铺
     * 小径用的料,而它 {@code asItem()} 是空气——模型照文档写,工具回一句"不是可
     * 放置方块"。
     */
    private static Item substituteItem(Block block) {
        if (block instanceof net.minecraft.world.level.block.FarmBlock
                || block instanceof net.minecraft.world.level.block.DirtPathBlock) {
            return Items.DIRT;
        }
        if (block == Blocks.TALL_GRASS) {
            return Items.SHORT_GRASS;
        }
        if (block == Blocks.LARGE_FERN) {
            return Items.FERN;
        }
        return block.asItem();
    }

    /** 只有一种方块吗——单色时可以跳过逐格取样。 */
    public boolean isSingle() {
        return entries.size() == 1;
    }

    public Entry first() {
        return entries.get(0);
    }

    /**
     * 取这一格该用哪个方块。
     *
     * <p>位置哈希而非随机数:调色板的意义是"看起来自然",不是"每次都不同"。
     * 每次都不同反而是灾难——玩家点头确认的那张预览和最终盖出来的会是两栋房子。
     */
    public Entry pick(BlockPos pos) {
        if (entries.size() == 1) {
            return entries.get(0);
        }
        long h = pos.getX() * 341873128712L
                + pos.getY() * 1971648029L
                + pos.getZ() * 132897987541L;
        h ^= h >>> 29;
        h *= 0xff51afd7ed558ccdL;
        h ^= h >>> 32;
        int roll = (int) Math.floorMod(h, totalWeight);
        for (Entry e : entries) {
            roll -= e.weight();
            if (roll < 0) {
                return e;
            }
        }
        return entries.get(entries.size() - 1);
    }
}
