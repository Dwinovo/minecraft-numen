package com.dwinovo.numen.plugins.tlm;

import com.mojang.serialization.Codec;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.world.entity.Entity;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

/**
 * 她穿哪套女仆模型:身体的属性,住在身体上。
 *
 * <p>记成 NeoForge 的数据附件挂在同伴的实体上:随玩家数据存盘(休眠、死后重建都带着),并由 NeoForge 同步给每一个正在看到她的
 * 客户端——新玩家进入视野、登录、换维度时它会自己补发,不需要我们另发包。每个客户端的渲染钩子读的是同步来的这份,
 * 不是哪个人本地的设置。
 *
 * <p>没穿就是没有这份附件(读出 null),不存空串。这个类两侧都会被加载,不引用任何客户端类。
 */
public final class Outfit {

    private static final DeferredRegister<AttachmentType<?>> TYPES =
            DeferredRegister.create(NeoForgeRegistries.ATTACHMENT_TYPES, "numen_tlm");

    private static final DeferredHolder<AttachmentType<?>, AttachmentType<String>> MODEL = TYPES.register("tlm_model",
            () -> AttachmentType.builder(() -> "")
                    .serialize(Codec.STRING)
                    .sync(ByteBufCodecs.STRING_UTF8)
                    .build());

    private Outfit() {}

    /** 附件类型要在注册事件前挂上总线。 */
    static void register(IEventBus modBus) {
        TYPES.register(modBus);
    }

    /** 这具身体穿的模型 id;没穿返回 null。服务端读的是真值,客户端读的是同步来的那份。 */
    public static String worn(Entity body) {
        return body.getExistingDataOrNull(MODEL.get());
    }

    /** 服务端:换一套;{@code modelId} 传 null 表示脱下。 */
    static void wear(Entity body, String modelId) {
        if (modelId == null) {
            body.removeData(MODEL.get());
        } else {
            body.setData(MODEL.get(), modelId);
        }
    }
}
