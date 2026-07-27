package com.dwinovo.numen.core.follow;

import com.dwinovo.numen.agent.tool.ToolRegistry;
import com.dwinovo.numen.task.CompanionTaskFactory;

public final class FollowOwnerFeature {
    private static boolean registered;

    private FollowOwnerFeature() {
    }

    public static synchronized void register() {
        if (registered) {
            return;
        }
        CompanionTaskFactory.register(FollowOwnerTaskRecord.class, FollowOwnerCompanionTask::new);
        ToolRegistry.register(new FollowOwnerTool());
        registered = true;
    }
}
