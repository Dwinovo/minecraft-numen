package com.dwinovo.numen;

import com.dwinovo.numen.debug.client.PathDebugRenderer;
import com.dwinovo.numen.debug.client.PathDebugState;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RegisterDebugRenderersEvent;
import net.neoforged.neoforge.common.NeoForge;

/** NeoForge 的客户端入口:只接寻路调试的覆盖层——下行快照、世界里画线、断线清空。 */
@Mod(value = Constants.MOD_ID, dist = Dist.CLIENT)
public class NumenContentNeoForgeClient {

    public NumenContentNeoForgeClient(IEventBus modBus) {
        PathDebugState.install();
        // 世界里画线走原版 gizmo 通道:注册进 DebugRenderer 的渲染器列表(NeoForge 注册的不受 F3 调试项门控,常驻),
        // 每帧提取期在 gizmo 收集器作用域内被调用,没有快照时什么都不画
        modBus.addListener((RegisterDebugRenderersEvent event) ->
                event.register((camX, camY, camZ, debugValues, frustum, partialTick) -> PathDebugRenderer.emit()));
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut event) -> PathDebugState.clear());
    }
}
