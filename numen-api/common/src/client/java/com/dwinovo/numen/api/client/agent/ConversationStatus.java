package com.dwinovo.numen.api.client.agent;

/**
 * 一个会话此刻头像右下角该标哪一种:同一时刻只有一种,是"她此刻最需要你知道的那件事";什么都不标就是闲着、你都看过了。
 * 左栏、转盘、消息通知都按这一处算,画法在 {@code StatusDot}。纯 JVM,可单测。
 */
public enum ConversationStatus {
    /** 什么都不标。 */
    NONE,
    /** 她正在想或正在跑程序:转圈。 */
    WORKING,
    /** 这个会话里有你还没看过的她的话:蓝点。 */
    NEW,
    /** 她的循环因为需要你处理的原因停下了:红点。 */
    ERROR,
    /** 有一条征询或她的提问在等你答:黄点。 */
    WAITING;

    /**
     * 算状态用的事实。
     *
     * @param group   群聊会话:只会是"有新话"
     * @param online  这只同伴在世界里;不在线的头像变灰,不标任何状态
     * @param waiting 有一条征询挂着,或她的提问还没答
     * @param error   循环停在需要主人处理的原因上(连不上模型、没绑模型档案这类)
     * @param unseen  她最后一句话比主人最后看到的时刻新
     * @param working 她在想或在跑程序
     */
    public record Facts(boolean group, boolean online, boolean waiting, boolean error, boolean unseen, boolean working) {}

    /** 按先后取第一个成立的:等你 &gt; 出错 &gt; 有新话 &gt; 在干活。 */
    public static ConversationStatus of(Facts f) {
        if (f.group()) {
            return f.unseen() ? NEW : NONE;
        }
        if (!f.online()) {
            return NONE;
        }
        if (f.waiting()) return WAITING;
        if (f.error()) return ERROR;
        if (f.unseen()) return NEW;
        if (f.working()) return WORKING;
        return NONE;
    }
}
