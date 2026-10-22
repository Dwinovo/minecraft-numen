package com.dwinovo.numen.gametest;

import com.dwinovo.numen.plugins.ysm.NumenYsm;
import com.dwinovo.numen.plugins.ysm.Ysm;
import com.dwinovo.numen.plugins.ysm.YsmHost;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.INBTSerializable;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.registries.NeoForgeRegistries;
import net.neoforged.neoforge.registries.RegisterEvent;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * YSM 的替身:联动对 YSM 的全部依赖是两处,这里各放一个——命令({@code ysm play}、{@code ysm model set},收到的每一条都记下)
 * 与玩家 NBT 里的 {@code model_id}(NeoForge 上是名为 {@code yes_steve_model:model_id} 的数据附件)。联动经 {@link NumenYsm#install}
 * 装上,和真装了 YSM 时走同一个入口。为什么不挂真 YSM 见本模块的 build.gradle。
 */
@EventBusSubscriber(modid = "numen")
final class YsmStub {

    /** 收到的命令,{@code play <名字> <动作>} 或 {@code model <名字> <模型> <贴图>},按到达顺序。 */
    static final List<String> COMMANDS = new CopyOnWriteArrayList<>();

    /** 玩家身上的 {@code model_id} 附件:{@code model_id} 与 {@code select_texture} 两个键,和 YSM 存的一样。 */
    private static final class ModelInfo implements INBTSerializable<CompoundTag> {
        private final CompoundTag tag = new CompoundTag();

        @Override
        public CompoundTag serializeNBT(HolderLookup.Provider provider) {
            return tag.copy();
        }

        @Override
        public void deserializeNBT(HolderLookup.Provider provider, CompoundTag nbt) {
            tag.merge(nbt);
        }
    }

    private static final AttachmentType<ModelInfo> MODEL_ID = AttachmentType.serializable(ModelInfo::new).build();

    private static boolean installed;

    @SubscribeEvent
    static void register(RegisterEvent event) {
        event.register(NeoForgeRegistries.Keys.ATTACHMENT_TYPES,
                ResourceLocation.fromNamespaceAndPath("yes_steve_model", "model_id"), () -> MODEL_ID);
    }

    /** 在服务器上放好命令并装上联动;一次跑批只做一回。 */
    static synchronized void install(MinecraftServer server) {
        if (installed) return;
        installed = true;
        server.getCommands().getDispatcher().register(Commands.literal("ysm")
                .then(Commands.literal("play").then(Commands.argument("targets", EntityArgument.players())
                        .then(Commands.argument("animation", StringArgumentType.string()).executes(ctx -> {
                            String animation = StringArgumentType.getString(ctx, "animation");
                            for (ServerPlayer p : EntityArgument.getPlayers(ctx, "targets")) {
                                COMMANDS.add("play " + p.getName().getString() + " " + animation);
                            }
                            return 1;
                        }))))
                .then(Commands.literal("model").then(Commands.literal("set")
                        .then(Commands.argument("targets", EntityArgument.players())
                                .then(Commands.argument("model", StringArgumentType.string())
                                        .then(Commands.argument("texture", StringArgumentType.string())
                                                .executes(ctx -> {
                                                    String model = StringArgumentType.getString(ctx, "model");
                                                    String texture = StringArgumentType.getString(ctx, "texture");
                                                    for (ServerPlayer p : EntityArgument.getPlayers(ctx, "targets")) {
                                                        COMMANDS.add("model " + p.getName().getString() + " " + model
                                                                + " " + texture);
                                                        CompoundTag info = new CompoundTag();
                                                        info.putString("model_id", model);
                                                        info.putString("select_texture", texture);
                                                        p.getData(MODEL_ID).deserializeNBT(p.registryAccess(), info);
                                                    }
                                                    return 1;
                                                })))))));
        NumenYsm.install(new YsmHost() {
            @Override
            public void onServerTick(Consumer<MinecraftServer> listener) {
                NeoForge.EVENT_BUS.addListener((ServerTickEvent.Post e) -> listener.accept(e.getServer()));
            }

            @Override
            public Ysm.Storage storage() {
                return Ysm.Storage.NEOFORGE;
            }
        }, null);
    }

    /** 某个同伴收到的命令,按顺序。 */
    static List<String> sentTo(String name) {
        return COMMANDS.stream().filter(c -> c.split(" ")[1].equals(name)).toList();
    }
}
