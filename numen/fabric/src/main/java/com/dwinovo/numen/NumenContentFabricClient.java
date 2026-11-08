package com.dwinovo.numen;

import com.dwinovo.numen.debug.client.PathDebugRenderer;
import com.dwinovo.numen.debug.client.PathDebugState;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;

/** Fabric 的客户端入口:只接寻路调试的覆盖层——世界里画线、断线清空(下行快照的收包由网络层在 {@code NumenContent.init} 登记时挂上)。 */
public class NumenContentFabricClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        PathDebugState.install();
        WorldRenderEvents.AFTER_TRANSLUCENT.register(context -> {
            if (context.matrixStack() != null) {
                PathDebugRenderer.render(context.matrixStack(), context.camera());
            }
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> PathDebugState.clear());
    }
}
