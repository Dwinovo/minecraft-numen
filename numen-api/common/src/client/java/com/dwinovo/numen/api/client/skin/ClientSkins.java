package com.dwinovo.numen.api.client.skin;

import com.dwinovo.numen.api.client.ClientMachines;
import com.dwinovo.numen.api.client.agent.ClientCompanions;
import com.dwinovo.numen.api.data.ModLanguageData.Keys;
import com.dwinovo.numen.api.owner.Libraries;
import com.dwinovo.numen.api.skin.SkinError;
import com.dwinovo.numen.api.skin.SkinProfile;
import com.dwinovo.numen.api.skin.SkinRef;
import com.dwinovo.numen.api.skin.SkinSigning;
import com.dwinovo.numen.api.skin.Wardrobe;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * 客户端换皮肤的把手:皮肤选择器与召唤卡都从这里走同一条流程({@link Wardrobe},这台电脑上的签名队与签名缓存),
 * 签好的皮肤经服务端的库协议({@link Libraries#SKIN})交给它穿上;服务端不收(验签没过、不是她的主人)的原因交回 {@code answer}。
 *
 * <p>线程:客户端主线程。
 */
public final class ClientSkins {

    private static final Wardrobe.Dress SERVER = new Wardrobe.Dress() {
        @Override
        public void wear(UUID companion, SkinRef ref, SkinProfile skin, Consumer<String> answer) {
            JsonObject body = body(companion);
            body.addProperty("ref", ref.toString());
            body.addProperty("value", skin.value());
            body.addProperty("signature", skin.signature());
            ClientCompanions.library().request(Libraries.SKIN, Libraries.PUT, body,
                    reply -> answer.accept(reply.ok() ? null : reply.problem()));
        }

        @Override
        public void takeOff(UUID companion, Consumer<String> answer) {
            ClientCompanions.library().request(Libraries.SKIN, Libraries.DELETE, body(companion),
                    reply -> answer.accept(reply.ok() ? null : reply.problem()));
        }
    };

    private ClientSkins() {}

    private static JsonObject body(UUID companion) {
        JsonObject body = new JsonObject();
        body.addProperty("companion", companion.toString());
        return body;
    }

    /**
     * 给她换上选定的这张图。
     *
     * @return 排上队的编号;已经签过、当场交给服务端了是 {@code 0};排满了是 {@code -1}
     * @throws SkinError 图不能当皮肤用
     */
    public static long wear(UUID companion, SkinChoices.Choice choice, String arm, SkinSigning.Listener progress) {
        return ClientMachines.get().wardrobe().wear(companion, choice.name(), new SkinSigning.Source.Image(choice.png()),
                arm, SERVER, progress);
    }

    /** 脱掉她的皮肤,回到默认皮肤。 */
    public static void takeOff(UUID companion, Consumer<String> answer) {
        SERVER.takeOff(companion, answer);
    }

    /** 这台电脑上在排的签名。 */
    public static List<SkinSigning.Pending> pending() {
        return ClientMachines.get().wardrobe().pending();
    }

    /**
     * 召唤卡上选的皮肤,她到了以后穿上:没办成的(图不能用、队排满、签名失败、服务端不收)作为一条消息告诉主人。
     */
    public static void wearOnArrival(UUID companion, SkinChoices.Pick pick) {
        String name = pick.choice().name();
        try {
            long ticket = wear(companion, pick.choice(), pick.variant(), new SkinSigning.Listener() {
                @Override
                public void done(SkinSigning.Outcome outcome) {
                    if (!outcome.ok()) {
                        tell(Component.translatable(Keys.SKIN_NOT_WORN, name, outcome.problem().message()));
                    }
                }
            });
            if (ticket < 0) {
                tell(Component.translatable(Keys.SKIN_QUEUE_FULL, SkinSigning.MAX_PENDING));
            }
        } catch (SkinError unusable) {
            tell(Component.translatable(Keys.SKIN_NOT_WORN, name, unusable.message()));
        }
    }

    private static void tell(Component message) {
        Minecraft game = Minecraft.getInstance();
        if (game.player != null) {
            game.player.sendSystemMessage(message);
        }
    }
}
