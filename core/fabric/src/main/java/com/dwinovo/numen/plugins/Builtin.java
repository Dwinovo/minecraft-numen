package com.dwinovo.numen.plugins;

import com.dwinovo.numen.core.ModJar;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.permission.Action;
import com.dwinovo.numen.permission.Facts;
import com.dwinovo.numen.permission.Permission;
import com.dwinovo.numen.permission.TerritoryClaims;
import com.dwinovo.numen.plugins.ysm.Ysm;
import com.dwinovo.numen.plugins.ysm.YsmHost;
import eu.pb4.common.protection.api.CommonProtection;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

import java.nio.file.Path;
import java.util.function.Consumer;

/**
 * 本加载器内嵌了哪些联动、各自要谁——以及加载器替它们做的那几件事。
 *
 * <p>清单在这里,不做扫描:内嵌联动是<b>闭合集合</b>,数量由我们自己定;扫描是给开放
 * 集合用的。列在一处,"现在内嵌了哪些、各自要谁"一眼答得完。闸门本身三个加载器共用,
 * 见 {@link Gate}。
 *
 * <p>YSM 与 Common Protection API;车万女仆没有 Fabric 版,这里装不上它,联动也就无从谈起。
 *
 * <h2>联动的类型只许出现在嵌套类里</h2>
 * 本类自己的方法(含 lambda 编译出来的合成方法)一个都不能提联动的类型:校验器为了核对
 * 参数类型会把它们提前加载,而开发运行(datagen、runClient)里联动不在类路径上——
 * compileOnly——提前加载就是 {@code ClassNotFoundException},整个模组入口跟着炸。
 * 各联动的接线放进各自的嵌套类,闸门开了才碰到它。
 */
public final class Builtin {

    private Builtin() {}

    public static void registerAll() {
        Gate gate = new Gate(FabricLoader.getInstance()::isModLoaded, ModJar::find);
        gate.open("yes_steve_model", "ysm", skills -> () -> YsmOnFabric.install(skills));
        gate.open("common-protection-api", () -> CommonProtectionClaims::install);
    }

    /**
     * 领地裁决口接 Common Protection API(Patbox):Flan、GOML、Open Parties and Claims 这些领地 mod 在它
     * 上面登记自己的判断,这里一处问遍。同伴以她自己的身份问——领地 mod 看见的是一个没被信任的玩家,
     * 主人要让她在自己的领地里干活,就在领地 mod 里信任她,和信任别的玩家一样。
     *
     * <p>动词对到 CPA 的问法:挖问 {@code canBreakBlock},放问 {@code canPlaceBlock},右键方块与从容器拿
     * 问 {@code canInteractBlock},打问 {@code canDamageEntity},右键实体问 {@code canInteractEntity};
     * 丢东西不落在哪一格上,领地管不着。
     */
    private static final class CommonProtectionClaims implements TerritoryClaims {
        static void install() {
            Permission.useTerritoryClaims(new CommonProtectionClaims());
        }

        @Override
        public boolean forbids(Action action, Facts facts) {
            ServerLevel level = facts.live();
            NumenPlayer self = facts.actor();
            return switch (action.kind()) {
                case BREAK -> !CommonProtection.canBreakBlock(level, action.pos(), self.getGameProfile(), self);
                case PLACE -> !CommonProtection.canPlaceBlock(level, action.pos(), self.getGameProfile(), self);
                case USE_BLOCK, TAKE -> action.pos() != null
                        && !CommonProtection.canInteractBlock(level, action.pos(), self.getGameProfile(), self);
                case ATTACK -> !CommonProtection.canDamageEntity(level, action.entity(), self.getGameProfile(), self);
                case USE_ENTITY ->
                        !CommonProtection.canInteractEntity(level, action.entity(), self.getGameProfile(), self);
                case DROP -> false;
            };
        }
    }

    /** YSM 联动只写原版;它要的加载器专属的两件事,Fabric 的答案在这里。 */
    private static final class YsmOnFabric implements YsmHost {
        static void install(Path skills) {
            com.dwinovo.numen.plugins.ysm.NumenYsm.install(new YsmOnFabric(), skills);
        }

        @Override
        public void onServerTick(Consumer<MinecraftServer> listener) {
            ServerTickEvents.END_SERVER_TICK.register(listener::accept);
        }

        @Override
        public Ysm.Storage storage() {
            return Ysm.Storage.FABRIC;
        }
    }
}
