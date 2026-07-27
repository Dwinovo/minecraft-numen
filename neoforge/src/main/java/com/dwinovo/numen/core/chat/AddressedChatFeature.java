package com.dwinovo.numen.core.chat;

import com.dwinovo.numen.client.agent.AgentLoopRegistry;
import com.dwinovo.numen.client.agent.NumenRoster;
import net.neoforged.neoforge.client.event.ClientChatEvent;
import net.neoforged.neoforge.common.NeoForge;

public final class AddressedChatFeature {
    private static boolean registered;

    private AddressedChatFeature() {
    }

    public static synchronized void register() {
        if (registered) {
            return;
        }

        NeoForge.EVENT_BUS.addListener(ClientChatEvent.class, AddressedChatFeature::onClientChat);
        registered = true;
    }

    public static void onClientChat(ClientChatEvent event) {
        String message = event.getMessage();
        if (message == null) {
            return;
        }

        String prompt = message.trim();
        if (prompt.isEmpty() || prompt.startsWith("/")) {
            return;
        }

        for (NumenRoster.Entry companion : NumenRoster.instance().entries()) {
            if (AddressedChatMatcher.isAddressed(prompt, companion.name())) {
                AgentLoopRegistry.getOrCreate(companion.uuid()).submitPrompt(prompt);
            }
        }
    }
}
