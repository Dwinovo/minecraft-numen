package com.dwinovo.numen.inventory;

import com.dwinovo.numen.Constants;
import net.minecraft.world.inventory.MenuType;
import net.minecraftforge.common.extensions.IForgeMenuType;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/** Forge menu registrations owned by the bundled API module. */
public final class NumenMenus {
    private static final DeferredRegister<MenuType<?>> MENUS =
            DeferredRegister.create(ForgeRegistries.MENU_TYPES, Constants.MOD_ID);

    public static final RegistryObject<MenuType<CompanionInventoryMenu>> COMPANION_INVENTORY =
            MENUS.register("companion_inventory", () -> IForgeMenuType.create(
                    (containerId, inventory, data) ->
                            new CompanionInventoryMenu(containerId, inventory, data.readUUID(), null)));

    private NumenMenus() { }

    public static void register(IEventBus bus) {
        MENUS.register(bus);
    }
}
