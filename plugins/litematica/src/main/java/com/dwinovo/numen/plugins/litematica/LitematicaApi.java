package com.dwinovo.numen.plugins.litematica;

import com.dwinovo.numen.api.NumenApi;
import com.dwinovo.numen.api.agent.script.ApiError;
import com.dwinovo.numen.api.agent.script.ErrorKind;
import com.dwinovo.numen.api.sdk.ClientCall;
import com.dwinovo.numen.api.sdk.Doc;
import com.dwinovo.numen.api.sdk.Example;
import com.dwinovo.numen.api.sdk.Fn;
import com.dwinovo.numen.api.sdk.Note;
import com.dwinovo.numen.api.sdk.SeeAlso;

import java.util.List;

/**
 * {@code litematica}:主人用 Litematica 画的图纸。
 *
 * <h2>为什么在主人客户端跑</h2>
 * 图纸文件在主人的电脑上,而 Numen 的图纸库在服务端。列有哪些、把哪一张搬过去,都要读主人本机的文件,
 * 所以两个函数都取 {@code ClientCall}——它们在主人客户端上执行。搬的目标是服务端的图纸目录;单机时
 * 服务端工作目录就是游戏目录,搬过去就等于放进 Numen 读的那个目录,{@code numen.build.blueprint} 随即
 * 认得它。连别人的服务器时两端不是一台机器,搬不了,如实报错而不是假装成功。
 *
 * <p>本组只认文件与 Litematica 的配置文件,不引用 Litematica 的任何类,也不引它的 API。
 */
public final class LitematicaApi {

    private LitematicaApi() {}

    static void install(NumenApi numen) {
        numen.api("schematic", "Your owner's Litematica schematics: what they have, and bringing one into the "
                + "server's blueprint folder so numen.build can build it.", LitematicaApi.class);
    }

    /** 要同步哪一张。 */
    public record Sync(@Doc("The schematic's name, without the .litematic extension, exactly as litematica.list "
            + "lists it.") String name) {}

    /** 同步的结果。 */
    public record Synced(@Doc("The schematic's name.") String name,
                         @Doc("Whether the file was copied; false when it was already in the blueprint folder, "
                                 + "which is the usual case in single player.") boolean copied,
                         @Doc("The blueprint folder it is in now.") String folder) {}

    @Fn("The Litematica schematics your owner has drawn.")
    @Example("litematica.schematic.list()")
    @Note("Read-only. Runs on your owner's client, where Litematica keeps its schematics. Sync one with "
            + "litematica.schematic.sync, then build it with numen.build.blueprint and numen.build.raise.")
    @SeeAlso({"litematica.schematic.sync", "numen.build.blueprint"})
    public static List<String> list(ClientCall call) {
        return Litematica.list();
    }

    @Fn("Bring one of your owner's Litematica schematics into the server's blueprint folder, so numen.build can "
            + "build it.")
    @Example("litematica.schematic.sync(\"my_house\")")
    @Note("Only when your owner hosts the world on this computer (single player, or a server they started): a "
            + "schematic file on their client is not a file on a server they merely joined. Once it is in, build it "
            + "with numen.build.blueprint(name, pos) and numen.build.raise.")
    @SeeAlso({"litematica.schematic.list", "numen.build.blueprint", "numen.build.raise"})
    public static Synced sync(ClientCall call, Sync args) {
        if (!Litematica.hosting()) {
            throw new ApiError(ErrorKind.FAILED, "this only works when your owner hosts the world on this computer; "
                    + "on a server they joined, their schematic file is not on that server", null);
        }
        try {
            boolean copied = Litematica.copy(args.name());
            return new Synced(args.name(), copied, Litematica.blueprintDir().toString());
        } catch (IllegalArgumentException e) {
            throw new ApiError(ErrorKind.NOT_FOUND, e.getMessage(), null);
        }
    }
}
