package com.dwinovo.numen.core.mixin;

import com.dwinovo.numen.core.scaffold.TemporaryScaffoldController;
import com.dwinovo.numen.core.scaffold.TemporaryScaffoldLedger;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.task.TaskResult;
import com.dwinovo.numen.task.TaskState;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(targets = "com.dwinovo.numen.core.task.base.AbstractCompanionTask")
public abstract class TaskResultScaffoldReportMixin {
    @Shadow @Final protected NumenPlayer player;

    @Inject(method = "buildResult", at = @At("RETURN"), cancellable = true)
    private void numen$appendTemporaryScaffoldReport(
        TaskState state,
        CallbackInfoReturnable<TaskResult> callback
    ) {
        TemporaryScaffoldController.refreshReasons(this.player);
        List<TemporaryScaffoldLedger.Report> reports = TemporaryScaffoldLedger.reports(
            this.player.getUUID()
        );
        if (reports.isEmpty()) {
            return;
        }

        TaskResult original = callback.getReturnValue();
        Map<String, Object> data = new LinkedHashMap<>();
        if (original.data() != null) {
            data.putAll(original.data());
        }

        List<Map<String, Object>> details = new ArrayList<>();
        for (TemporaryScaffoldLedger.Report report : reports) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("dimension", report.dimensionId());
            item.put("x", report.x());
            item.put("y", report.y());
            item.put("z", report.z());
            item.put("block", report.placedBlockId());
            item.put("reason", report.reason());
            item.put("reason_text", TemporaryScaffoldController.explainReason(report.reason()));
            details.add(Map.copyOf(item));
        }
        data.put("temporary_scaffolds_not_reclaimed", List.copyOf(details));

        String coordinateSummary = reports.stream()
            .map(report -> "(" + report.x() + "," + report.y() + "," + report.z() + ") "
                + report.placedBlockId() + ": "
                + TemporaryScaffoldController.explainReason(report.reason()))
            .collect(Collectors.joining("; "));
        String message = original.message() + " Temporary scaffolds not reclaimed: "
            + coordinateSummary;
        callback.setReturnValue(
            new TaskResult(
                original.success(),
                message,
                original.timedOut(),
                original.interrupted(),
                Map.copyOf(data)
            )
        );
    }
}
