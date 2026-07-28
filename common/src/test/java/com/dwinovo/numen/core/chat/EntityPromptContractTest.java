package com.dwinovo.numen.core.chat;

import org.junit.jupiter.api.Test;

import com.dwinovo.numen.core.chat.EntityPromptContract;

public final class EntityPromptContractTest {
    @Test
    void verifiedRuntimeBehavior() {
        String original = "Call auto_mine for blocks. move_to is a background task.";
        String amended = EntityPromptContract.apply(original);
        String lower = amended.toLowerCase();
        String normalized = amended.replaceAll("\\s+", " ");

        require(!amended.contains("auto_mine"), "obsolete auto_mine name must be removed");
        require(!amended.contains("move_to"), "obsolete move_to name must be removed");
        require(lower.contains("call mine"), "the registered mine tool must be named");
        require(amended.contains("goto is a background task"), "the registered goto name must be used");
        require(
            amended.contains("MUST use mine"),
            "explicit block mining and gathering must use the dedicated task"
        );
        require(
            amended.contains("scan_blocks may be used only when target availability"),
            "a survey must remain available when requested targets are genuinely unknown"
        );
        require(
            amended.contains("After a scan finds the requested blocks, call mine"),
            "a successful survey must transition to the dedicated mining task"
        );
        require(
            amended.contains("Never substitute goto plus interact_at"),
            "coordinate-by-coordinate mining routes must be rejected"
        );
        require(
            normalized.contains("remove armor") && normalized.contains("head, chest, legs, and feet"),
            "plain-language armor removal must route through all four named equipment slots"
        );
        require(
            amended.contains("Use transfer") && amended.contains("Never use drop_items"),
            "armor removal must preserve the original item stacks instead of dropping them"
        );
        require(
            amended.contains("do not call equip_item again until the owner explicitly asks"),
            "removed armor must stay off until the owner asks to equip it again"
        );
        require(
            normalized.contains("empty hands")
                && normalized.contains("currently selected mainhand slot and the named offhand slot"),
            "plain-language empty-hand requests must target the actual selected hand slots"
        );
        require(
            normalized.contains("task_status") && normalized.contains("task_stop"),
            "hand clearing must stop an active task before it can select another tool"
        );
        require(
            normalized.contains("Never guess a hotbar slot")
                && normalized.contains("do not switch to another occupied hotbar slot"),
            "emptying the main hand must preserve the selected empty hotbar slot"
        );
        require(
            normalized.contains("If transfer refuses a move or no empty backpack slot exists")
                && normalized.contains("Never drop, overwrite, retry forever, or claim that the slot is empty"),
            "a full inventory or binding restriction must be reported instead of silently losing or misreporting gear"
        );
        require(
            normalized.contains("origin=self") && normalized.contains("origin=owner")
                && normalized.contains("explicitly says around the owner/player"),
            "ordinary nearby combat must center on the AI unless the owner is explicitly named as the center"
        );
        require(
            normalized.contains("level_scope=same_plane") && normalized.contains("level_scope=all")
                && normalized.contains("explicitly requests targets at other heights"),
            "ordinary nearby combat must stay in one Y plane unless other heights are explicitly requested"
        );
        require(
            normalized.contains("range scan") && normalized.contains("not camera direction")
                && normalized.contains("line of sight remains an attack-time safety check"),
            "nearby discovery must use the bounded cylindrical range while attack visibility stays intact"
        );
        require(
            normalized.contains("primary goal is sleep")
                && normalized.contains("call sleep directly")
                && normalized.contains("must not call follow_owner"),
            "sleep requests must keep sleep as the primary goal instead of routing come to follow_owner"
        );
        require(
            normalized.contains("romanized sleep intent")
                && normalized.contains("laishuijiao")
                && normalized.contains("qushuijiao")
                && normalized.contains("Never use interact_at on a bed"),
            "romanized sleep requests must route to the verified sleep task"
        );
        require(
            normalized.contains("initial accepted reply is not sleep success")
                && normalized.contains("task_finished(status=done)"),
            "an accepted asynchronous task must not be reported as completed sleep"
        );
        require(
            normalized.contains("generic request to eat")
                && normalized.contains("ordinary safe food")
                && normalized.contains("golden carrot only when no ordinary safe food is available"),
            "generic eating requests must preserve the safe food value order"
        );
        require(
            normalized.contains("Risky food is a last famine fallback only when hunger is 6 or lower")
                && normalized.contains("Do not use a golden apple, enchanted golden apple, or healing potion")
                && normalized.contains("recovery policy selects it")
                && normalized.contains("Naming the exact item does not override this safety rule"),
            "generic eating must preserve risky and emergency consumables"
        );
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
