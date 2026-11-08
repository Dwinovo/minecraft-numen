package com.dwinovo.numen.tools;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** 读一块告示牌({@code numen.scan.block} 交回的 sign):正反两面各四行字,有没有涂蜡。 */
@Tag("mc")
class SignsTest {

    @BeforeAll
    static void boot() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    @Test
    void bothSidesAndTheWaxAreRead() {
        // 没有世界的告示牌改不了字(改字要通知世界),照存档读进来
        SignBlockEntity sign = new SignBlockEntity(BlockPos.ZERO, Blocks.OAK_SIGN.defaultBlockState());
        net.minecraft.nbt.CompoundTag saved = new net.minecraft.nbt.CompoundTag();
        saved.put("front_text", net.minecraft.world.level.block.entity.SignText.CODEC.encodeStart(
                net.minecraft.nbt.NbtOps.INSTANCE, net.minecraft.world.level.block.entity.SignText.EMPTY.asMutable()
                        .setLine(0, Component.literal("front words")).asImmutable()).getOrThrow());
        saved.put("back_text", net.minecraft.world.level.block.entity.SignText.CODEC.encodeStart(
                net.minecraft.nbt.NbtOps.INSTANCE, net.minecraft.world.level.block.entity.SignText.EMPTY.asMutable()
                        .setLine(1, Component.literal("back words")).asImmutable()).getOrThrow());
        sign.loadWithComponents(net.minecraft.world.level.storage.TagValueInput.create(net.minecraft.util.ProblemReporter.DISCARDING,
                net.minecraft.data.registries.VanillaRegistries.createWorldLookup(), saved));
        Signs.Text text = Signs.read(sign);
        assertEquals(List.of("front words", "", "", ""), text.front());
        assertEquals(List.of("", "back words", "", ""), text.back());
        assertFalse(text.waxed());
    }
}
