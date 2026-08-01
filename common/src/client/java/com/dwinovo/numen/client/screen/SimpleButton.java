package com.dwinovo.numen.client.screen;

import com.dwinovo.numen.client.ui.RoundRect;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/**
 * The Numen button, drawn procedurally from the CURRENT theme (no baked sprite, so a
 * theme switch recolours it): a rounded card — idle = field tone, hover = lifted toward
 * white, disabled = sunk toward the ground. {@link #primary()} paints it in the theme's
 * CTA colour for the one action a form wants you to take.
 *
 * <p>{@link #icon(ResourceLocation)} swaps the text label for a WHITE-template sprite
 * tinted with the label colour — icon buttons stay theme-aware too. Pair icons with a
 * vanilla {@code setTooltip} so the meaning is one hover away.
 */
public final class SimpleButton extends Button {

    private ResourceLocation icon;
    private boolean primary;
    private boolean danger;

    public SimpleButton(int x, int y, int width, int height, Component message, Button.OnPress onPress) {
        super(x, y, width, height, message, onPress,
                defaultNarrationSupplier -> defaultNarrationSupplier.get());
    }

    /** Draw a centered, theme-tinted WHITE icon sprite instead of the text label. */
    public SimpleButton icon(ResourceLocation icon) {
        this.icon = icon;
        return this;
    }

    /** CTA styling (amber fill) — the one action a form wants you to take. */
    public SimpleButton primary() {
        this.primary = true;
        return this;
    }

    /** Destructive styling (fail-red fill) — for the confirm card's Delete. */
    public SimpleButton danger() {
        this.danger = true;
        return this;
    }

    @Override
    protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        UiTheme t = UiTheme.current();
        int x = getX(), y = getY(), w = getWidth(), h = getHeight();
        boolean hovered = active && isHoveredOrFocused();

        int base = danger ? t.fail() : primary ? t.cta() : t.field();
        int fill = !active ? UiTheme.mix(base, t.ground(), 0.55f)
                : hovered ? UiTheme.mix(base, 0xFFFFFFFF, 0.18f)
                : base;
        int border = !active ? UiTheme.mix(t.border(), t.ground(), 0.45f) : t.border();
        RoundRect.card(g, x, y, x + w, y + h, 5, fill, border);

        // danger 的深红底上深棕字不可读——用 onBand 奶油浅色(五套预设的 fail 都是深红系)。
        int labelColor = !active ? UiTheme.mix(t.textDim(), t.ground(), 0.3f)
                : danger ? t.onBand()
                : primary ? t.onCta()
                : t.text();

        if (icon != null) {   // white template sprite, multiplied by the label colour
            int s = Math.min(w, h) >= 16 ? 11 : 9;
            g.setColor(((labelColor >> 16) & 0xFF) / 255f, ((labelColor >> 8) & 0xFF) / 255f,
                    (labelColor & 0xFF) / 255f, 1f);
            g.blitSprite(icon, x + (w - s) / 2, y + (h - s) / 2, s, s);
            g.setColor(1f, 1f, 1f, 1f);
            return;
        }

        Font font = Minecraft.getInstance().font;
        int tw = font.width(getMessage());
        Nb.text(g, font, getMessage(), x + (w - tw) / 2, y + (h - 8) / 2, labelColor);   // flat, coloured
    }
}
