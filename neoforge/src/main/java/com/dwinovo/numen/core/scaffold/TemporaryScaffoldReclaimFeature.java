package com.dwinovo.numen.core.scaffold;

import com.dwinovo.numen.agent.tool.ToolRegistry;
import com.dwinovo.numen.task.CompanionTaskFactory;

public final class TemporaryScaffoldReclaimFeature {
    private static boolean registered;

    private TemporaryScaffoldReclaimFeature() {
    }

    public static synchronized void register() {
        if (registered) {
            return;
        }
        CompanionTaskFactory.register(
            TemporaryScaffoldReclaimTaskRecord.class,
            TemporaryScaffoldReclaimCompanionTask::new
        );
        ToolRegistry.register(new TemporaryScaffoldReclaimTool());
        registered = true;
    }
}
