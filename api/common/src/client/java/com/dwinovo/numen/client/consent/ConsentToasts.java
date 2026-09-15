package com.dwinovo.numen.client.consent;

import com.dwinovo.numen.client.agent.NumenRoster;
import com.dwinovo.numen.data.ModLanguageData;
import com.dwinovo.numen.network.payload.ConsentRequestPayload;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.gui.components.toasts.ToastComponent;
import net.minecraft.client.gui.components.toasts.TutorialToast;
import net.minecraft.network.chat.Component;

import java.util.UUID;

/**
 * 征询在界面外的提醒,全用原版的 toast——右上角,和"按 E 打开物品栏"是同一种东西:
 * <ul>
 *   <li>有请求挂着:教程 toast"小蓝 想征得你的同意 / 按 <b>Y</b> 答复",请求没了自己收起;</li>
 *   <li>答完、主人没答就撤了:系统 toast 说一句答了什么、为什么撤。</li>
 * </ul>
 * 请求的内容不在这里——按键打开答复框看。
 */
public final class ConsentToasts {

    private static final SystemToast.SystemToastId NOTICE = new SystemToast.SystemToastId();

    private ConsentToasts() {}

    /** 一条请求到了。每条请求一张:顶替的请求换一张,主人看得出这是新的一问。 */
    static void asking(ConsentRequestPayload request) {
        toasts().addToast(new Asking(request));
    }

    /** 主人答了。 */
    static void answered(UUID companion, Component what) {
        toasts().addToast(SystemToast.multiline(Minecraft.getInstance(), NOTICE,
                Component.translatable(ModLanguageData.Keys.CONSENT_ANSWERED, name(companion)), what));
    }

    /** 主人没答就撤了(超时、任务结束……)。 */
    static void withdrawn(UUID companion, String why) {
        toasts().addToast(SystemToast.multiline(Minecraft.getInstance(), NOTICE,
                Component.translatable(ModLanguageData.Keys.CONSENT_WITHDRAWN, name(companion)),
                Component.literal(why)));
    }

    private static ToastComponent toasts() {
        return Minecraft.getInstance().getToasts();
    }

    private static String name(UUID companion) {
        String name = NumenRoster.instance().name(companion);
        return name == null ? "?" : name;
    }

    /** 挂着一条请求的教程 toast:这条请求不再挂着就收起。 */
    private static final class Asking extends TutorialToast {

        private final ConsentRequestPayload request;

        Asking(ConsentRequestPayload request) {
            super(Icons.SOCIAL_INTERACTIONS,
                    Component.translatable(ModLanguageData.Keys.CONSENT_TITLE, name(request.companion())),
                    Component.translatable(ModLanguageData.Keys.CONSENT_TOAST_HINT,
                            Component.keybind(ModLanguageData.Keys.KEY_TALK_COMPANION).withStyle(ChatFormatting.BOLD)),
                    false);
            this.request = request;
        }

        @Override
        public Visibility render(GuiGraphics g, ToastComponent toasts, long timeSinceLastVisible) {
            if (ConsentCards.pending(request.companion()) != request) {
                hide();
            }
            return super.render(g, toasts, timeSinceLastVisible);
        }

        @Override
        public Object getToken() {
            return request.id();
        }
    }
}
