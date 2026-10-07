package com.dwinovo.numen;

import com.dwinovo.numen.debug.PathDebugPayload;
import com.dwinovo.numen.debug.client.PathDebugRenderer;
import com.dwinovo.numen.debug.client.PathDebugState;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;

/** Fabric 的客户端入口:只接寻路调试的覆盖层——下行快照、世界里画线、断线清空。 */
public class NumenCoreFabricClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        PathDebugState.install();
        ClientPlayNetworking.registerGlobalReceiver(PathDebugPayload.TYPE,
                (payload, context) -> context.client().execute(() -> PathDebugPayload.handle(payload)));
        WorldRenderEvents.AFTER_TRANSLUCENT.register(context -> {
            if (context.matrixStack() != null) {
                PathDebugRenderer.render(context.matrixStack(), context.camera());
            }
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> PathDebugState.clear());
    }
}
