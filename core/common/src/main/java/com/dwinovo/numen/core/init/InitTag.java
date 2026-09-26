package com.dwinovo.numen.core.init;

import com.dwinovo.numen.core.Constants;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;

/**
 * Catalogue of the datapack tags the numen-core tool pack declares. The
 * pathfinder and the loader-side data generators both reference the constants
 * here so the key's identifier exists in one place only — rename or repath in
 * this file and every consumer follows. These are core's tags (namespace
 * {@code numen}), not the engine's: pathfinding scaffolding and blueprint safety
 * are tool-pack concerns. Which blocks the body may break is not a tag — that is
 * the permission layer's rule table ({@code com.dwinovo.numen.permission}).
 *
 * <h2>Why not derive at runtime</h2>
 * Tags are referenced from pathfinder hot paths where a fresh
 * {@link ResourceLocation#fromNamespaceAndPath} per call would allocate. Caching
 * the {@link TagKey} as a {@code static final} field amortises that cost
 * and gives the JIT a constant pool reference.
 */
public final class InitTag {

    /**
     * Foods that may be used to feed/heal a companion. Datapack-driven so server
     * admins can extend the list without code changes — see
     * {@code data/numen/tags/item/tame_foods.json}.
     */
    public static final TagKey<Item> TAME_FOODS = item("tame_foods");

    /**
     * The factory throwaway list: blocks a new companion is willing to spend while
     * travelling — bridging gaps, stepping up, and pillaring. Each companion then keeps
     * her own list ({@code throwaway} commands); this tag is only where it starts.
     * Datapack-driven so packs can add their own cheap blocks — see
     * {@code data/numen/tags/item/throwaway.json}.
     */
    public static final TagKey<Item> THROWAWAY = item("throwaway");

    /**
     * Blocks whose block-entity data a blueprint may carry into the world — sign
     * text, banner patterns, and whatever a pack chooses to add.
     *
     * <p>The tag <b>is</b> the authorisation. A blueprint is a file: editable,
     * downloadable. Copying a chest's contents out of one would print items from
     * nothing, so nothing is copied unless it is named here. Being a datapack tag
     * rather than a list in code means a pack that adds decorative block entities
     * can declare them safe without touching the mod — but it also means adding a
     * container here lets blueprints print its contents. That is the pack author's
     * call to make, deliberately, and it should be made knowing that.
     *
     * <p>Named vanilla tags are preferred over listing members: {@code
     * #minecraft:banners} keeps meaning "banners" across versions. See
     * {@code data/numen/tags/block/safe_block_entity_data.json}.
     */
    public static final TagKey<Block> SAFE_BLOCK_ENTITY_DATA = block("safe_block_entity_data");

    private InitTag() {}

    /** 模型写标签用的前缀:{@code #minecraft:beds} 指"床这一类",而不是某一种颜色的床。 */
    public static final String TAG_PREFIX = "#";

    /**
     * 把 {@code #ns:path} 解成一个标签键;不是这个形式、或者 id 不合法,返回 {@code null}
     * (调用方接着按具体 id 试)。
     *
     * <p>让工具参数收标签,是因为"一类方块"这件事我们枚举不完:床有 16 色、石头有一族、
     * 模组还会加。标签是 Minecraft 自己表达"一类"的方式,而且整合包能扩。
     */
    public static <T> TagKey<T> parseRef(net.minecraft.resources.ResourceKey<
            ? extends net.minecraft.core.Registry<T>> registry, String raw) {
        if (raw == null || !raw.startsWith(TAG_PREFIX)) {
            return null;
        }
        ResourceLocation id = ResourceLocation.tryParse(raw.substring(TAG_PREFIX.length()).trim());
        return id == null ? null : TagKey.create(registry, id);
    }

    private static TagKey<Item> item(String name) {
        return TagKey.create(Registries.ITEM,
                ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, name));
    }

    private static TagKey<Block> block(String name) {
        return TagKey.create(Registries.BLOCK,
                ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, name));
    }
}
