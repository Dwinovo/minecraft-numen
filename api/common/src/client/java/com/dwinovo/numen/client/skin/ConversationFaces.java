package com.dwinovo.numen.client.skin;

import com.dwinovo.numen.agent.conversation.Conversation;
import com.dwinovo.numen.client.agent.Conversations;
import com.dwinovo.numen.client.agent.KnownSkins;
import net.minecraft.client.gui.GuiGraphics;

import java.util.List;
import java.util.UUID;

/**
 * 一个会话的脸:就他俩是她的脸,多人是叠脸(前两张,右下错开)——同一条规则,没有两种图标。
 * 左栏与转盘共用,叠脸的错位只在这里定一次。
 */
public final class ConversationFaces {

    private ConversationFaces() {}

    public static void draw(GuiGraphics g, Conversation c, int x, int y, int size) {
        Conversations convos = Conversations.instance();
        UUID her = convos.soloOf(c);
        if (her != null) {
            CompanionFace.draw(g, her, KnownSkins.of(her), x, y, size);
            return;
        }
        List<UUID> faces = convos.membersAlive(c);
        int small = size * 7 / 10;
        int step = size - small;
        for (int k = 0; k < Math.min(2, faces.size()); k++) {
            UUID m = faces.get(k);
            CompanionFace.draw(g, m, KnownSkins.of(m), x + k * step, y + k * step, small);
        }
    }
}
