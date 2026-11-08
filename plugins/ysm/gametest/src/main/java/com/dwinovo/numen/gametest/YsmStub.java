package com.dwinovo.numen.gametest;

import com.dwinovo.numen.plugins.ysm.NumenYsm;
import com.dwinovo.numen.plugins.ysm.Ysm;
import com.dwinovo.numen.plugins.ysm.YsmHost;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.CapabilityManager;
import net.minecraftforge.common.capabilities.CapabilityToken;
import net.minecraftforge.common.capabilities.ICapabilitySerializable;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.event.AttachCapabilitiesEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * YSM 的替身:联动对 YSM 的全部依赖是两处,这里各放一个——命令({@code ysm play}、{@code ysm model set},收到的每一条都记下)
 * 与玩家 NBT 里的 {@code model_id}(Forge 上是名为 {@code yes_steve_model:model_id} 的能力,存在 {@code ForgeCaps} 下)。联动经 {@link NumenYsm#install}
 * 装上,和真装了 YSM 时走同一个入口。为什么不挂真 YSM 见本模块的 build.gradle。
 */
@Mod.EventBusSubscriber(modid = "numen")
public final class YsmStub {

    /** 收到的命令,{@code play <名字> <动作>} 或 {@code model <名字> <模型> <贴图>},按到达顺序。 */
    static final List<String> COMMANDS = new CopyOnWriteArrayList<>();

    /** 玩家身上的 {@code model_id} 能力:{@code model_id} 与 {@code select_texture} 两个键,和 YSM 存的一样。 */
    private static final class ModelInfo implements ICapabilitySerializable<CompoundTag> {
        private final CompoundTag tag = new CompoundTag();
        private final LazyOptional<ModelInfo> self = LazyOptional.of(() -> this);

        @Override
        public <T> LazyOptional<T> getCapability(Capability<T> capability, Direction side) {
            return MODEL_ID.orEmpty(capability, self);
        }

        @Override
        public CompoundTag serializeNBT() {
            return tag.copy();
        }

        @Override
        public void deserializeNBT(CompoundTag nbt) {
            tag.merge(nbt);
        }
    }

    private static final Capability<ModelInfo> MODEL_ID = CapabilityManager.get(new CapabilityToken<>() {});

    private static boolean installed;

    /** 每个玩家挂一份,存盘时 Forge 把它写进 {@code ForgeCaps} 下的 {@code yes_steve_model:model_id}。 */
    @SubscribeEvent
    public static void attach(AttachCapabilitiesEvent<Entity> event) {
        if (event.getObject() instanceof Player) {
            event.addCapability(new ResourceLocation("yes_steve_model", "model_id"), new ModelInfo());
        }
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
                                                        p.getCapability(MODEL_ID).ifPresent(m -> m.deserializeNBT(info));
                                                    }
                                                    return 1;
                                                })))))));
        NumenYsm.install(new YsmHost() {
            @Override
            public void onServerTick(Consumer<MinecraftServer> listener) {
                MinecraftForge.EVENT_BUS.addListener((TickEvent.ServerTickEvent e) -> {
                    if (e.phase == TickEvent.Phase.END) {
                        listener.accept(e.getServer());
                    }
                });
            }

            @Override
            public Ysm.Storage storage() {
                return Ysm.Storage.FORGE;
            }
        }, null);
    }

    /** 某个同伴收到的命令,按顺序。 */
    static List<String> sentTo(String name) {
        return COMMANDS.stream().filter(c -> c.split(" ")[1].equals(name)).toList();
    }
}
