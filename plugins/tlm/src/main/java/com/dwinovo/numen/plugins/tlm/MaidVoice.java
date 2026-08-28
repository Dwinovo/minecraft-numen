package com.dwinovo.numen.plugins.tlm;

import com.github.tartaricacid.touhoulittlemaid.client.sound.data.MaidSoundInstanceAtPos;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.sounds.SoundEvents;

import java.util.UUID;

/**
 * 同伴的语音——不是说话,是<b>不说话的那些时刻</b>。
 *
 * <h2>它和 TTS 是两回事</h2>
 * 她说什么由 TTS 念(引擎自带那几条线)。这里放的是模型包自带的语音:受伤哼一声、
 * 高兴时应一声。跟着模型走——换成企鹅模型就是企鹅的声音。
 *
 * <h2>怎么绕开 EntityMaid</h2>
 * {@code MaidSoundInstance} 的构造器要真女仆实体,但它还有个按坐标播的兄弟
 * {@code MaidSoundInstanceAtPos},只收 x/y/z。而车万女仆挂在 {@code PlaySoundSourceEvent}
 * 上的钩子判的是<b>接口</b>({@code instanceof ICustomSoundBuffer})不是具体类,所以
 * 按坐标播的那个照样会被接管、把音频挂到声道上。一个女仆实体都不需要。
 *
 * <h2>为什么没有"闲置时自动哼"</h2>
 * 七个 idle 音循环播,一个宠物在你旁边一直哼,很快就从"有生气"变成"烦"。
 * 什么时候出声交给她自己判断({@code make_sound} 工具)——那本来就是大模型擅长的事,
 * 而且省掉了"她现在忙不忙"那套判据。
 */
public final class MaidVoice {

    /** 声音 id 里的分类前缀,和音效包的目录结构一一对应。 */
    public static final String HURT = "maid/ai/hurt";
    public static final String DEATH = "maid/ai/death";
    public static final String IDLE = "maid/mode/idle";
    public static final String ATTACK = "maid/mode/attack";

    private MaidVoice() {}

    /**
     * 在这只同伴身上播一条语音。她没穿女仆模型、或者那个包没带音效,就什么都不做。
     *
     * <p>只在客户端有意义:音效包是主人自己装的,只有这一侧读得到。
     */
    public static boolean play(UUID companion, String sound) {
        String pack = pack(companion);
        if (pack == null) return false;
        if (Tlm.voice(pack, sound).isEmpty()) return false;   // 这个包没带这条,别播个空的

        AbstractClientPlayer body = body(companion);
        if (body == null) return false;   // 不在视距内,没必要出声

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
    static String pack(UUID companion) {
        String modelId = Wardrobe.worn(companion);
        if (modelId == null) return null;
        int colon = modelId.indexOf(':');
        return colon > 0 ? modelId.substring(0, colon) : null;
    }

    /** 这只同伴现在能出哪些声。没穿模型、或包里不带语音,就是空的。 */
    static java.util.Set<String> available(UUID companion) {
        String pack = pack(companion);
        return pack == null ? java.util.Set.of() : Tlm.voices(pack);
    }

    /** 在世界里找到这只同伴的身体;不在视距内返回 null。 */
    private static AbstractClientPlayer body(UUID companion) {
        var level = Minecraft.getInstance().level;
        if (level == null) return null;
        for (var p : level.players()) {
            if (p.getUUID().equals(companion)) return p;
        }
        return null;
    }
}
