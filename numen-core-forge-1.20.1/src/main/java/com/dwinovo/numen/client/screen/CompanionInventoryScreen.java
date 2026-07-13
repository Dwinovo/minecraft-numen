package com.dwinovo.numen.client.screen;

import com.dwinovo.numen.client.agent.ClientNumenLookup;
import com.dwinovo.numen.client.agent.NumenRoster;
import com.dwinovo.numen.inventory.CompanionInventoryMenu;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;

/** Vanilla container interaction with the same top-level navigation as the companion workspace. */
public final class CompanionInventoryScreen extends AbstractContainerScreen<CompanionInventoryMenu> {
    private static final ResourceLocation INVENTORY_TEXTURE =
            new ResourceLocation("textures/gui/container/inventory.png");

    public CompanionInventoryScreen(CompanionInventoryMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        imageWidth = 364;
        imageHeight = 166;
        inventoryLabelY = -1000;
        titleLabelY = -1000;
    }

    @Override protected void init() {
        super.init();
        int total = Math.min(364, width - 12);
        int each = total / 5;
        int x = (width - total) / 2;
        int y = Math.max(4, topPos - 24);
        String[] labels = {"对话", "任务", "AI 设置", "AI 数据", "背包"};
        String[] pages = {"chat", "tasks", "settings", "data", "inventory"};
        for (int i = 0; i < labels.length; i++) {
            int buttonWidth = i == labels.length - 1 ? total - each * i : each;
            String page = pages[i];
            Button button = Button.builder(Component.literal(labels[i]), b -> openWorkspace(page))
                    .bounds(x + each * i, y, buttonWidth, 20).build();
            button.active = i != labels.length - 1;
            addRenderableWidget(button);
        }
    }

    private void openWorkspace(String page) {
        if (minecraft == null || minecraft.player == null) return;
        String name = NumenRoster.instance().entries().stream()
                .filter(entry -> entry.uuid().equals(menu.companionUuid()))
                .map(NumenRoster.Entry::name).findFirst().orElse("伙伴");
        minecraft.player.closeContainer();
        NumenScreen.openPage(menu.companionUuid(), name, page);
    }

    @Override protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        graphics.blit(INVENTORY_TEXTURE, leftPos, topPos, 0, 0, 176, 166);
        graphics.blit(INVENTORY_TEXTURE, leftPos + 188, topPos + 76, 0, 76, 176, 90);
        AbstractClientPlayer entity = ClientNumenLookup.resolve(menu.companionUuid());
        if (entity != null) {
            InventoryScreen.renderEntityInInventoryFollowsMouse(graphics, leftPos + 51, topPos + 75, 30,
                    (float) (leftPos + 51) - mouseX, (float) (topPos + 25) - mouseY, entity);
        }
    }

    @Override protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        graphics.drawString(font, Component.literal("合成"), 97, 6, 0x404040, false);
        graphics.drawString(font, Component.literal("伙伴物品"), 8, 74, 0x404040, false);
        graphics.drawString(font, Component.literal("你的背包"), 196, 74, 0x404040, false);
    }

    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        super.render(graphics, mouseX, mouseY, partialTick);
        renderTooltip(graphics, mouseX, mouseY);
    }
}
