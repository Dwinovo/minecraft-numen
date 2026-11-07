package com.example.nav;

import com.dwinovo.numen.api.sdk.Doc;
import com.dwinovo.numen.api.sdk.Example;
import com.dwinovo.numen.api.sdk.Fn;
import com.dwinovo.numen.api.sdk.Job;
import com.dwinovo.numen.api.sdk.Note;
import com.dwinovo.numen.api.sdk.ServerCall;

import net.minecraft.core.BlockPos;

/** {@code mynav.nav}:她朝一格方块直线走过去。 */
public final class NavApi {

    private NavApi() {}

    /** 走去哪一格。 */
    public record Cell(@Doc("The cell to walk to.") BlockPos cell) {}

    /** 走到了。 */
    @Doc("Where she stopped.")
    public record Arrived(@Doc("The cell she is standing in.") BlockPos at) {}

    @Fn("Walk in a straight line at a cell and stop when she is there; it gives up when she stops getting closer.")
    @Example("mynav.nav.go({x = 120, y = 64, z = -35})")
    @Note("Background work: the call returns when she has arrived or given up. It does not look for a way around walls.")
    public static Job<Arrived> go(ServerCall call, Cell args) {
        return Job.of(new GoRecord(call, args.cell()));
    }
}
