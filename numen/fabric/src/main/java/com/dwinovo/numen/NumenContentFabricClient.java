package com.dwinovo.numen;

import com.dwinovo.numen.debug.PathDebugPayload;
import com.dwinovo.numen.debug.client.PathDebugRenderer;
import com.dwinovo.numen.debug.client.PathDebugState;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.client.Minecraft;

/** Fabric 的客户端入口:只接寻路调试的覆盖层——下行快照、世界里画线、断线清空。 */
public class NumenContentFabricClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        PathDebugState.install();
        ClientPlayNetworking.registerGlobalReceiver(PathDebugPayload.TYPE,
                (payload, context) -> context.client().execute(() -> PathDebugPayload.handle(payload)));
        // 挂原版调试线的绘制点;事件不带相机,相机取 gameRenderer 的主相机
        WorldRenderEvents.BEFORE_DEBUG_RENDER.register(context -> {
            if (context.matrices() != null) {
                PathDebugRenderer.render(context.matrices(), Minecraft.getInstance().gameRenderer.getMainCamera());
            }
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> PathDebugState.clear());
    }
}
