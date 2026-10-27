package com.dwinovo.numen.tools.shape;

import com.dwinovo.numen.build.BuildStates;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.HolderSet;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 图案(pattern)的文字读成"带权重的几组方块":写法照 WorldEdit,方块一律由原版的 {@link BuildStates#parse} 读、标签由方块注册表查,
 * 全仓只有这一处。读出来的是数据,每一格掷骰子是用它的一方的事(Lua 模块):先按权重(相对的,不写 % 的是 1)选一组,再在这一组里等可能地
 * 选一个方块。
 *
 * <ul>
 *   <li>{@code stone}、{@code oak_stairs[facing=north]}:一组,一个方块;</li>
 *   <li>{@code 50%stone,50%cobblestone}:逗号分开(方括号与花括号里的逗号不算),每段前可以写权重;</li>
 *   <li>{@code ##logs}:这个标签里每个方块的默认状态各一个;{@code ##*logs}:每个方块的每一种状态各一个;</li>
 *   <li>{@code *oak_stairs[facing=north]}:没写的属性取遍所有值,写了的固定。</li>
 * </ul>
 */
final class BlockPatterns {

    private BlockPatterns() {}

    private static final Pattern WEIGHTED = Pattern.compile("([0-9]+(?:\\.[0-9]*)?)%(.*)", Pattern.DOTALL);

    /** @throws IllegalArgumentException 读不通(说是哪一段) */
    static List<ShapeApi.PatternChoice> parse(String text) {
        List<String> parts = split(text);
        if (parts.size() == 1) {
            return List.of(new ShapeApi.PatternChoice(1, blocks(parts.getFirst())));
        }
        List<ShapeApi.PatternChoice> out = new ArrayList<>();
        for (String part : parts) {
            Matcher weighted = WEIGHTED.matcher(part);
            if (weighted.matches()) {
                String body = weighted.group(2).trim();
                if (body.isEmpty()) {
                    throw new IllegalArgumentException("'" + part + "' has a weight but no block after the %");
                }
                out.add(new ShapeApi.PatternChoice(Double.parseDouble(weighted.group(1)), blocks(body)));
            } else {
                out.add(new ShapeApi.PatternChoice(1, blocks(part)));
            }
        }
        return out;
    }

    /** 按逗号切开,方括号与花括号里的逗号(状态列表、NBT)不切。 */
    static List<String> split(String text) {
        List<String> parts = new ArrayList<>();
        int depth = 0;
        int from = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '[' || c == '{') {
                depth++;
            } else if (c == ']' || c == '}') {
                depth--;
            } else if (c == ',' && depth == 0) {
                parts.add(text.substring(from, i).trim());
                from = i + 1;
            }
        }
        parts.add(text.substring(from).trim());
        return parts;
    }

    /** 一段(没有权重了)是哪几个方块,等可能。 */
    private static List<String> blocks(String body) {
        if (body.startsWith("##")) {
            return tagged(body.substring(2));
        }
        if (body.startsWith("*")) {
            return anyState(body.substring(1));
        }
        BuildStates.parse(body, true);
        return List.of(body);
    }

    private static List<String> tagged(String written) {
        boolean everyState = written.startsWith("*");
        HolderSet.Named<Block> tag = tag(everyState ? written.substring(1) : written);
        List<String> out = new ArrayList<>();
        tag.forEach(holder -> {
            Block block = holder.value();
            if (everyState) {
                block.getStateDefinition().getPossibleStates().forEach(s -> out.add(BlockStateParser.serialize(s)));
            } else {
                out.add(BlockStateParser.serialize(block.defaultBlockState()));
            }
        });
        return out;
    }

    /** 方块标签(写法里 {@code ##} 之后的 id):图案与蒙版查标签只有这一处。 */
    static HolderSet.Named<Block> tag(String written) {
        String id = written.toLowerCase(Locale.ROOT);
        ResourceLocation location = ResourceLocation.tryParse(id);
        Optional<HolderSet.Named<Block>> tag = location == null ? Optional.empty()
                : BuiltInRegistries.BLOCK.getTag(TagKey.create(Registries.BLOCK, location));
        if (tag.isEmpty()) {
            throw new IllegalArgumentException("'" + id + "' is no block tag");
        }
        if (tag.get().size() == 0) {
            throw new IllegalArgumentException("the block tag '" + id + "' has no blocks");
        }
        return tag.get();
    }

    private static List<String> anyState(String written) {
        BlockStateParser.BlockResult read = BuildStates.parse(written, true);
        if (read.nbt() != null) {
            throw new IllegalArgumentException("'*" + written + "' picks block states, which carry no NBT");
        }
        List<String> out = new ArrayList<>();
        for (BlockState s : read.blockState().getBlock().getStateDefinition().getPossibleStates()) {
            if (read.properties().entrySet().stream().allMatch(e -> s.getValue(e.getKey()).equals(e.getValue()))) {
                out.add(BlockStateParser.serialize(s));
            }
        }
        return out;
    }
}
