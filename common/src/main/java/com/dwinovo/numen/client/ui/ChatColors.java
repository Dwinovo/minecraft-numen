package com.dwinovo.numen.client.ui;

/**
 * The chat-app palette, hand-tuned against {@link com.dwinovo.numen.client.screen.UiTheme#WARM}'s
 * tan ground (#CBA87B) — the single active theme. Centralised here so the panel, the plan card
 * and the HUD toasts share one definition; folds into {@code UiTheme} when theme switching lands.
 */
public final class ChatColors {

    private ChatColors() {}

    /** Faintest text tier (placeholders, pending items, empty-state hints). */
    public static final int FAINT = 0xFF8C7C62;
    /** Companion bubble: cream card lifting off the tan ground. */
    public static final int AI_FILL = 0xFFF2E9D2, AI_BORDER = 0xFFA99062;
    /** Owner bubble: soft amber (the theme's CTA warmth, desaturated for body text). */
    public static final int OWN_FILL = 0xFFEDC98F, OWN_BORDER = 0xFFC1913B;
    /** Queued prompt: a half-present owner bubble (still waiting for a splice point). */
    public static final int QUEUED_FILL = 0x80EDC98F, QUEUED_BORDER = 0x80C1913B;
    /** Tool chip: translucent dark wash — status, not a message. */
    public static final int CHIP_FILL = 0x22352818;
    /** Sidebar card (plan panel): a fainter wash of the same dark. */
    public static final int CARD_FILL = 0x16352818;
}
