package com.dwinovo.numen.tools.body;

import com.dwinovo.numen.api.NumenApi;
import com.dwinovo.numen.api.entity.NumenPlayer;
import com.dwinovo.numen.api.sdk.Doc;
import com.dwinovo.numen.api.sdk.Example;
import com.dwinovo.numen.api.sdk.Fn;
import com.dwinovo.numen.api.sdk.Note;
import com.dwinovo.numen.api.sdk.Omitted;
import com.dwinovo.numen.api.sdk.SeeAlso;
import com.dwinovo.numen.api.sdk.ServerCall;
import com.dwinovo.numen.script.ApiError;
import com.dwinovo.numen.script.ErrorKind;

import java.util.Optional;

/**
 * {@code numen.body}:她自己的身体本身——此刻朝哪看、把视线转到哪去。看得到的那一份在 {@code numen.status.self},
 * 这里只管改:读与写分开,是因为"我朝哪"是状态,"转过去"是动作,一个当场答、一个当场生效。
 */
public final class BodyApi {

    private BodyApi() {}

    public static void install(NumenApi numen) {
        numen.api("body", "Your own body: where you look, and turning where you look.", BodyApi.class);
    }

    /** 她要转到哪。两个轴都可以不写,不写的那个留在原处。 */
    public record Turn(@Doc("Which way to face, in degrees: 0 south, 90 west, 180 north, -90 east.")
                       @Omitted("keep facing the way you do") Optional<Double> yaw,
                       @Doc("How far up or down: -90 straight up, 0 the horizon, 90 straight down.")
                       @Omitted("keep the up-or-down you have") Optional<Double> pitch) {}

    /** 转完之后朝哪。 */
    @Doc("Where you look now.")
    public record Turned(@Doc("Which way you face, in degrees.") double yaw,
                         @Doc("How far up or down you look, in degrees.") double pitch) {}

    /**
     * 转过去,照原版玩家推鼠标:角度落在鼠标转得到的格子上,所以写 -90 转回来可能是 -89.99,和真人一样。
     * 走着的路上朝向归走路管({@code numen.move.walk} 每一刻朝行进方向看),这一下会被盖掉;要转就等停下来再转。
     */
    @Fn("Turn where you look, as a player does with the mouse: face another way, or look up and down.")
    @Example("numen.body.turn({pitch = -90})   -- look straight up")
    @Example("numen.body.turn({yaw = 90})      -- face west")
    @Example("numen.body.turn({yaw = 0, pitch = 0})   -- face south, level")
    @Note("Instant and read-only on the world: nothing around you changes, only where your eyes point.")
    @Note("The angle lands on the mouse grid, as a hand would: ask for -90 and read back something like -89.99.")
    @Note("A trip under way aims you where it goes, every tick: a turn while walking is undone the next tick. "
            + "Turn once you have stopped.")
    @Note("What you can see from where you stand is `numen.scan.sight`; your own body, including where you look now, "
            + "is `numen.status.self`.")
    @SeeAlso({"numen.status.self", "numen.scan.sight", "numen.scan.map"})
    public static Turned turn(ServerCall call, Turn args) {
        NumenPlayer her = call.her();
        double yaw = args.yaw().orElse((double) her.getYRot());
        double pitch = args.pitch().orElse((double) her.getXRot());
        if (pitch < -90.0 || pitch > 90.0) {
            throw new ApiError(ErrorKind.BAD_ARGUMENT, "pitch is -90 to 90: " + pitch, null);
        }
        her.look().turn((float) yaw, (float) pitch);
        return new Turned(her.getYRot(), her.getXRot());
    }
}
