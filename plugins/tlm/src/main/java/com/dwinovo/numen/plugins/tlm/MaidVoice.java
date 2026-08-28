package com.dwinovo.numen.plugins.tlm;

import com.github.tartaricacid.touhoulittlemaid.client.sound.data.MaidSoundInstanceAtPos;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.player.Player;

import java.util.UUID;

/**
 * 同伴挨打和死掉时的叫声,用当前女仆模型自带的语音。
 *
 * <h2>它和 TTS 是两回事</h2>
 * 她说什么由 TTS 念(引擎自带那几条线)。这里是模型包自带的语音,跟着模型走
 * ——换成企鹅模型就是企鹅的叫声。
 *
 * <h2>怎么绕开 EntityMaid</h2>
 * {@code MaidSoundInstance} 的构造器要真女仆实体,但它还有个按坐标播的兄弟
 * {@code MaidSoundInstanceAtPos},只收 x/y/z。而车万女仆挂在 {@code PlaySoundSourceEvent}
 * 上的钩子判的是<b>接口</b>({@code instanceof ICustomSoundBuffer})不是具体类,所以
 * 按坐标播的那个照样会被接管、把音频挂到声道上。一个女仆实体都不需要。
 *
 * <h2>只做被动发声</h2>
 * 挨打和死亡是<b>反应</b>,由事件驱动,她没有选择权。
 *
 * <p>"她自己决定什么时候出声"(下雨了应一声、干活时哼一句)那部分暂时不做:声音 id
 * 是<b>数据约定不是硬规范</b>——车万女仆代码里一个 id 都没写死,加载器按目录扫出来,
 * 所以第三方包完全可以造新 id。要让她挑,得先想清楚可选清单从哪来、怎么让她知道,
 * 那是另一个题目,不该顺手带过。
 */
public final class MaidVoice {

    private MaidVoice() {}

    /**
     * 挨打的反应。<b>按伤害来源挑</b>——烧着了和被人打是两种叫法,包里本来就分开录了。
     * 这个包没录细分的就退回通用的 {@code hurt}。
     */
    public static void onHurt(UUID companion, DamageSource source) {
        String id = "maid/ai/hurt";
        if (source != null) {
            if (source.is(DamageTypeTags.IS_FIRE)) {
                id = "maid/ai/hurt_fire";
            } else if (source.getEntity() instanceof Player) {
                id = "maid/ai/hurt_player";
            }
        }
        if (!play(companion, id)) play(companion, "maid/ai/hurt");
    }

    /** 死亡的反应。 */
    public static void onDeath(UUID companion) {
        play(companion, "maid/ai/death");
    }

    /** 真的播出去了返回 true。没穿模型、包里没这条、人不在视距内都返回 false。 */
    private static boolean play(UUID companion, String sound) {
        String pack = pack(companion);
        if (pack == null) return false;
        if (Tlm.voice(pack, sound).isEmpty()) return false;

        AbstractClientPlayer body = body(companion);
        if (body == null) return false;

        Minecraft.getInstance().getSoundManager().play(new MaidSoundInstanceAtPos(
                SoundEvents.EMPTY, pack + ":" + sound,
                body.getX(), body.getY() + body.getEyeHeight(), body.getZ(),
                1.0F, 1.0F));
        return true;
    }

    /**
     * 这只同伴此刻的音效包 id。<b>就是模型 id 的命名空间</b>——同一个包同时提供
     * 模型和声音,所以不需要另存一份"她用哪个音效包"。没穿模型返回 null。
     */
    private static String pack(UUID companion) {
        String modelId = Wardrobe.worn(companion);
        if (modelId == null) return null;
        int colon = modelId.indexOf(':');
        return colon > 0 ? modelId.substring(0, colon) : null;
    }

    private static AbstractClientPlayer body(UUID companion) {
        var level = Minecraft.getInstance().level;
        if (level == null) return null;
        for (var p : level.players()) {
            if (p.getUUID().equals(companion)) return p;
        }
        return null;
    }
}
