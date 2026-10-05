package com.dwinovo.numen.plugins.tlm;

import com.dwinovo.numen.entity.NumenPlayer;
import net.minecraft.nbt.StringTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.CapabilityManager;
import net.minecraftforge.common.capabilities.CapabilityToken;
import net.minecraftforge.common.capabilities.ICapabilitySerializable;
import net.minecraftforge.common.capabilities.RegisterCapabilitiesEvent;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.event.AttachCapabilitiesEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.Optional;

/**
 * 她穿哪套女仆模型:身体的属性,住在身体上。
 *
 * <p>记成 Forge 的实体能力({@link Slot})挂在同伴的实体上:随玩家数据存盘(休眠、死后重建都带着,写在 {@code ForgeCaps} 里),
 * 并由本类自己的一条频道同步给每一个正在看到她的客户端——换一套时发给正在看她的人,新玩家进入视野(登录、换维度、走近)时
 * 由 {@code StartTracking} 补发。每个客户端的渲染钩子读的是同步来的这份,不是哪个人本地的设置。服务端只给同伴挂,客户端给所有
 * 玩家挂(同伴在客户端就是一个远端玩家实体,装同步来的这一份)。
 *
 * <p>没穿就是读出 null,存的是空串。这个类两侧都会被加载,不引用任何客户端类;客户端那一半在 {@link OutfitClient}。
 *
 * <p>频道接受对端没有它:没装车万女仆的客户端照样能连,只是不会收到这条同步。
 */
public final class Outfit {

    private static final String VERSION = "1";
    private static final ResourceLocation ID = new ResourceLocation("numen_tlm", "outfit");

    private static final Capability<Slot> SLOT = CapabilityManager.get(new CapabilityToken<>() {});

    private static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(ID, () -> VERSION,
            NetworkRegistry.acceptMissingOr(VERSION), NetworkRegistry.acceptMissingOr(VERSION));

    /** 一具身体当前穿的模型 id;没穿是空串。 */
    private static final class Slot implements ICapabilitySerializable<StringTag> {
        private String model = "";
        private final LazyOptional<Slot> self = LazyOptional.of(() -> this);

        @Override
        public <T> LazyOptional<T> getCapability(Capability<T> cap, net.minecraft.core.Direction side) {
            return cap == SLOT ? self.cast() : LazyOptional.empty();
        }

        @Override
        public StringTag serializeNBT() {
            return StringTag.valueOf(model);
        }

        @Override
        public void deserializeNBT(StringTag tag) {
            model = tag.getAsString();
        }
    }

    /** 下行的同步:这个实体现在穿的模型,空串是脱下。 */
    private record Sync(int entityId, String model) {
        static void encode(Sync message, FriendlyByteBuf buf) {
            buf.writeVarInt(message.entityId);
            buf.writeUtf(message.model);
        }

        static Sync decode(FriendlyByteBuf buf) {
            return new Sync(buf.readVarInt(), buf.readUtf());
        }
    }

    private Outfit() {}

    /** 能力类型要在注册事件前挂上总线;频道与同步的钩子随安装一并接上。 */
    static void register(IEventBus modBus) {
        modBus.addListener((RegisterCapabilitiesEvent event) -> event.register(Slot.class));
        CHANNEL.registerMessage(0, Sync.class, Sync::encode, Sync::decode, (message, context) -> {
            context.get().enqueueWork(() -> OutfitClient.apply(message.entityId, message.model));
            context.get().setPacketHandled(true);
        }, Optional.of(NetworkDirection.PLAY_TO_CLIENT));

        MinecraftForge.EVENT_BUS.addGenericListener(Entity.class, (AttachCapabilitiesEvent<Entity> event) -> {
            if (event.getObject() instanceof Player player
                    && (player instanceof NumenPlayer || player.level().isClientSide())) {
                event.addCapability(ID, new Slot());
            }
        });
        MinecraftForge.EVENT_BUS.addListener((PlayerEvent.StartTracking event) -> {
            if (event.getTarget() instanceof NumenPlayer body && event.getEntity() instanceof ServerPlayer watcher
                    && !(watcher instanceof NumenPlayer)) {
                String model = worn(body);
                if (model != null) {
                    CHANNEL.send(PacketDistributor.PLAYER.with(() -> watcher), new Sync(body.getId(), model));
                }
            }
        });
    }

    /** 这具身体穿的模型 id;没穿返回 null。服务端读的是真值,客户端读的是同步来的那份。 */
    public static String worn(Entity body) {
        return body.getCapability(SLOT).resolve().map(slot -> slot.model).filter(model -> !model.isEmpty()).orElse(null);
    }

    /** 服务端:换一套;{@code modelId} 传 null 表示脱下。存在身体上,并告诉每个正在看她的客户端。 */
    static void wear(Entity body, String modelId) {
        String model = modelId == null ? "" : modelId;
        body.getCapability(SLOT).resolve().orElseThrow().model = model;
        CHANNEL.send(PacketDistributor.TRACKING_ENTITY.with(() -> body), new Sync(body.getId(), model));
    }

    /** 客户端:收到同步,记在那个实体上;实体已经不在视野里就算了。 */
    static void sync(Entity body, String model) {
        body.getCapability(SLOT).resolve().ifPresent(slot -> slot.model = model);
    }
}
