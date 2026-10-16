package com.dwinovo.numen.api.client.agent;

/**
 * 她的大脑在哪:主人的客户端({@link #CLIENT},旧的循环 {@link EntityAgentLoop})还是服务端({@link #SERVER},
 * 数据来自 {@link RemoteCompanions})。界面不问这个,只经 {@link CompanionViews} 拿视图;这一处是整个世界里唯一的开关。
 */
public enum BrainSite {
    CLIENT,
    SERVER;

    /** 现在用哪一处。 */
    public static final BrainSite CURRENT = SERVER;
}
