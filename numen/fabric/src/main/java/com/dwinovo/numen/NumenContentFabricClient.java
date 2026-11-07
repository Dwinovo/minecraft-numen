package com.dwinovo.numen;

import com.dwinovo.numen.debug.PathDebugPayload;
import com.dwinovo.numen.debug.client.PathDebugRenderer;
import com.dwinovo.numen.debug.client.PathDebugState;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;

/** Fabric 的客户端入口:只接寻路调试的覆盖层——下行快照、世界里画线、断线清空。 */
public class NumenContentFabricClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        PathDebugState.install();
        ClientPlayNetworking.registerGlobalReceiver(PathDebugPayload.TYPE,
                (payload, context) -> context.client().execute(() -> PathDebugPayload.handle(payload)));
        // 世界里画线走原版 gizmo 通道:BEFORE_GIZMOS 在 LevelRenderer.finalizeGizmoCollection() 之前触发,渲染帧的 gizmo
        // 收集器在位,回调里直接提交、当帧出画
        LevelRenderEvents.BEFORE_GIZMOS.register(context -> PathDebugRenderer.emit());
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> PathDebugState.clear());
    }
}
