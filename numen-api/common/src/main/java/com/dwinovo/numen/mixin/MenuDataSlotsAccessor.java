package com.dwinovo.numen.mixin;

import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.DataSlot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;

/**
 * Read a menu's synced data slots — the second channel of GUI state next to the item slots:
 * machine progress / fuel time / energy, the ints a real screen reads to draw progress bars.
 * {@code AbstractContainerMenu.dataSlots} is private and vanilla has no public getter; the lightest
 * cross-loader way to read it from common is an {@code @Accessor} (a generated getter — NOT a
 * code-injecting mixin). The AccessTransformer/Widener route is finicky for a shared-common widener
 * in this loom version (namespace remap), so the accessor is the clean choice here.
 */
@Mixin(AbstractContainerMenu.class)
public interface MenuDataSlotsAccessor {

    @Accessor("dataSlots")
    List<DataSlot> numen$dataSlots();
}
