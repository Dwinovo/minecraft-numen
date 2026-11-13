package com.dwinovo.numen.api.client.screen;

import com.dwinovo.numen.api.client.agent.KnownSkins;
import com.dwinovo.numen.api.client.skin.CompanionFace;
import com.dwinovo.numen.api.client.ui.NumenTheme;
import com.dwinovo.numen.api.client.ui.TextWrap;
import com.dwinovo.numen.api.client.ui.mc.McDrawSurface;
import com.dwinovo.numen.api.client.ui.widget.DialogBox;
import com.dwinovo.numen.api.client.ui.widget.TextField;
import com.dwinovo.numen.api.data.ModLanguageData.Keys;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.language.I18n;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 分享卡——创建者把一只同伴分享给一位玩家:头部是她的脸,右边填那位玩家的名字(可以此刻不在线);下面两段写明分享出去会怎样
 * (她若绑着你的档案,你不在线时朋友用她花的是你的;朋友看得到分享之前的对话与札记),右下取消/分享。后果就写在点头的这张卡上,
 * 不另过一张确认卡。
 *
 * <p>这里只验名字填了没有:有没有这位玩家、是不是已经在她的主人表上、够不够格分享,都由服务端判,结局回在聊天里
 * (和 {@code /numen player share} 同一句);办成了她的主人表多一行。
 */
final class SharePanel extends ModalCard {

    /** 屏幕侧的面:分享的是哪只、发出去、关卡。 */
    interface Host {
        UUID uuid();

        String name();

        void onShare(String player);

        void onClose();
    }

    /** 两段后果:头部下面隔开多少、两段之间隔多少、行距。 */
    private static final int NOTES_TOP = 2;
    private static final int NOTE_GAP = 4;
    private static final int LINE_H = 10;

    private final Host host;
    private String player = "";
    private TextField playerField;

    SharePanel(Host host) {
        this.host = host;
        Minecraft mc = Minecraft.getInstance();
        ui.setClipboard(() -> mc.keyboardHandler.getClipboard(), s -> mc.keyboardHandler.setClipboard(s));
        ui.setInputFactory(com.dwinovo.numen.api.client.ui.mc.McTextInput.factory());
    }

    /** 每次开卡:名字空着。 */
    @Override
    void reset() {
        player = "";
    }

    /** 那两段后果按卡的正文宽折好的行;段与段之间是一个 {@code null}。 */
    private List<String> notes() {
        var font = Minecraft.getInstance().font;
        int room = Math.min(width(), w > 0 ? w : width()) - DialogBox.PAD_X * 2;
        List<String> out = new ArrayList<>(TextWrap.wrap(I18n.get(Keys.SHARE_NOTE_COST), room, font::width, 0));
        out.add(null);
        out.addAll(TextWrap.wrap(I18n.get(Keys.SHARE_NOTE_HISTORY), room, font::width, 0));
        return out;
    }

    private int notesHeight() {
        int h = 0;
        for (String line : notes()) {
            h += line == null ? NOTE_GAP : LINE_H;
        }
        return h;
    }

    @Override
    int height() {
        return heightFor(COVER_H + NOTES_TOP + notesHeight());
    }

    @Override
    protected void layout(int top) {
        title(I18n.get(Keys.SHARE_TITLE, host.name()));
        playerField = field(coverRight(), coverFieldY(top), coverRightW(), I18n.get(Keys.SHARE_PLAYER),
                new TextField(player, v -> player = v).placeholder(I18n.get(Keys.SHARE_PLAYER_HINT)));
        buttons(I18n.get(Keys.GUI_SETTINGS_CANCEL), host::onClose, I18n.get(Keys.SHARE_CONFIRM), this::submit);
        ui.requestFocus(playerField);
    }

    /** 头部左边是她的脸;字段下面是那两段后果,次要色小字。 */
    @Override
    protected void paint(McDrawSurface s, NumenTheme.Colors c, int mouseX, int mouseY, long nowMs, float alpha) {
        CompanionFace.draw(s.graphics(), host.uuid(), KnownSkins.of(host.uuid()), photoX(), photoY(coverTop()), PHOTO);
        s.graphics().setColor(1f, 1f, 1f, alpha);
        int ty = coverTop() + COVER_H + NOTES_TOP;
        for (String line : notes()) {
            if (line == null) {
                ty += NOTE_GAP;
                continue;
            }
            s.drawText(line, x + DialogBox.PAD_X, ty, c.textSecondary(), false);
            ty += LINE_H;
        }
    }

    private void submit() {
        String name = player == null ? "" : player.strip();
        if (name.isEmpty()) {
            playerField.setError(I18n.get(Keys.SHARE_WARN_NAME));
            return;
        }
        host.onShare(name);
    }
}
