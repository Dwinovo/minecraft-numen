package com.dwinovo.numen.agent.tool;

import java.util.UUID;

/**
 * 一次工具调用锚定的同伴:服务端安全的最小面——只承诺稳定的实体 UUID。
 * 客户端实现({@code ClientToolContext},住在客户端源码集)另携带
 * 客户端实体引用供感知工具取视角;服务端代码({@code ServerToolTransport})
 * 只经这个接口读 UUID,编译期就摸不到任何客户端类。
 */
public interface ToolAnchor {

    /** 同伴的稳定 UUID(实体卸载出视距后依然有效)。 */
    UUID entityUuid();
}
