package com.dwinovo.numen;

import com.dwinovo.numen.debug.client.PathDebugRenderer;
import com.dwinovo.numen.debug.client.PathDebugState;

import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.common.MinecraftForge;

/**
 * Forge 的客户端入口:只接寻路调试的覆盖层——下行快照、世界里画线、断线清空。只在物理客户端上由 {@link NumenContentForge}
 * 调起,专用服务器上不加载这些客户端类型。
 */
final class NumenContentForgeClient {

    private NumenContentForgeClient() {}

    static void init() {
        PathDebugState.install();
        MinecraftForge.EVENT_BUS.addListener((RenderLevelStageEvent event) -> {
            if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
                PathDebugRenderer.render(event.getPoseStack(), event.getCamera());
            }
        });
        MinecraftForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut event) -> PathDebugState.clear());
    }
}
