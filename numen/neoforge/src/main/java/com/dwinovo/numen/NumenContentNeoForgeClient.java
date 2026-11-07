package com.dwinovo.numen;

import com.dwinovo.numen.debug.client.PathDebugRenderer;
import com.dwinovo.numen.debug.client.PathDebugState;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.common.NeoForge;

/** NeoForge 的客户端入口:只接寻路调试的覆盖层——下行快照、世界里画线、断线清空。 */
@Mod(value = Constants.MOD_ID, dist = Dist.CLIENT)
public class NumenContentNeoForgeClient {

    public NumenContentNeoForgeClient() {
        PathDebugState.install();
        NeoForge.EVENT_BUS.addListener((RenderLevelStageEvent event) -> {
            if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
                PathDebugRenderer.render(event.getPoseStack(), event.getCamera());
            }
        });
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut event) -> PathDebugState.clear());
    }
}
