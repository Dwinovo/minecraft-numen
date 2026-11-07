package com.dwinovo.numen.api.platform;

import com.dwinovo.numen.api.entity.FakeConnection;
import com.dwinovo.numen.api.entity.NumenPlayer;
import com.dwinovo.numen.api.platform.services.IBodyAdmission;
import net.minecraft.network.chat.Component;

import java.util.function.Consumer;

/** 模组上"玩家进来"没有要先问的插件,也没有加入后要做的事:当场接纳、什么也不做。 */
public final class FabricBodyAdmission implements IBodyAdmission {

    @Override
    public void admit(NumenPlayer body, FakeConnection connection, Consumer<Component> verdict) {
        verdict.accept(null);
    }

    @Override
    public void joined(NumenPlayer body) {
        // nothing
    }
}
