package com.dwinovo.numen.core.sleep;

import com.dwinovo.numen.agent.tool.ToolRegistry;
import com.dwinovo.numen.task.CompanionTaskFactory;

public final class SleepFeature {
    private static boolean registered;

    private SleepFeature() {
    }

    public static synchronized void register() {
        if (registered) {
            return;
        }
        CompanionTaskFactory.register(SleepTaskRecord.class, SleepCompanionTask::new);
        ToolRegistry.register(new SleepTool());
        registered = true;
    }
}
