package com.dwinovo.numen.plugins.jei;

import com.dwinovo.numen.api.sdk.Doc;
import net.minecraft.world.item.Item;

/** 查哪一样。 */
public record JeiQuery(@Doc("The item.") Item item) {}
