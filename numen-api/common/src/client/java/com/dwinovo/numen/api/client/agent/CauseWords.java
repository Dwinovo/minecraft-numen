package com.dwinovo.numen.api.client.agent;

import com.dwinovo.numen.api.agent.loop.Cause;
import com.dwinovo.numen.api.data.ModLanguageData.Keys;
import net.minecraft.client.resources.language.I18n;

/**
 * 原因({@link Cause})翻成当前语言的话,界面里说"为什么没成"的唯一一处:回合失败的聊天栏与 toast、没绑模型的提示、
 * 对话里的中断提示、设置里的检测按钮,同一种原因在哪里看到都是同一句话。话术纪律:说人话 + 说下一步,不甩堆栈
 * (堆栈的去处是日志,传输层已经记全了)。
 *
 * <p>文案只在语言文件里:调用没成与没绑模型借用已有的几条,切断与空回应是 {@code numen.cause.<码>}。
 * 码在 agent 模块定义;这里按显示时的语言现翻,所以日志里存下来的老记录换了语言也跟着变。
 */
public final class CauseWords {

    private CauseWords() {}

    public static String text(Cause cause) {
        return switch (cause.code()) {
            case Cause.UNAUTHORIZED -> withReply(cause, I18n.get(Keys.GUI_PROVIDERS_CHECK_UNAUTHORIZED));
            case Cause.NOT_FOUND -> withReply(cause, I18n.get(Keys.GUI_PROVIDERS_CHECK_NOT_FOUND));
            case Cause.RATE_LIMITED -> withReply(cause, I18n.get(Keys.GUI_PROVIDERS_CHECK_RATE_LIMITED));
            case Cause.SERVER_ERROR -> withReply(cause, I18n.get(Keys.GUI_PROVIDERS_CHECK_SERVER_ERROR));
            case Cause.BAD_REQUEST -> withReply(cause,
                    I18n.get(Keys.GUI_PROVIDERS_CHECK_BAD_REQUEST) + " (HTTP " + cause.arg(0) + ")");
            case Cause.NETWORK -> I18n.get(Keys.GUI_PROVIDERS_CHECK_NETWORK);
            case Cause.UNBOUND -> I18n.get(Keys.ENDPOINT_UNBOUND);
            case Cause.NO_KEY -> I18n.get(Keys.ENDPOINT_NO_KEY, cause.arg(0));
            case Cause.NO_URL -> I18n.get(Keys.ENDPOINT_NO_URL, cause.arg(0));
            case Cause.QUOTA -> I18n.get(Keys.ENDPOINT_QUOTA);
            default -> cause.arg(0).isEmpty() || !I18n.exists(key(cause) + "_by")
                    ? I18n.get(key(cause)) : I18n.get(key(cause) + "_by", cause.arg(0));
        };
    }

    /** 切断与空回应的文案键:{@code numen.cause.<码>};带现场的话(死因)的另有 {@code _by} 一条。 */
    private static String key(Cause cause) {
        return "numen.cause." + cause.code();
    }

    /**
     * 分类话术后面带上是谁回的、说了什么原话。同一个 401,key 发错了地方(回话来自另一家)和 key 本身不对
     * (厂家说 “User not found”)是两回事,只说“密钥无效”玩家和我们都分不出。
     */
    private static String withReply(Cause cause, String category) {
        String reply = cause.arg(2);
        if (reply.isEmpty()) return category;
        return category + " · " + I18n.get(Keys.GUI_PROVIDERS_CHECK_REPLY, cause.arg(1), reply);
    }
}
