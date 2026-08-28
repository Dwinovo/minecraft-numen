package com.dwinovo.numen.plugins.tlm;

import com.github.tartaricacid.touhoulittlemaid.client.sound.data.MaidSoundInstanceAtPos;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.player.Player;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 同伴的语音——不是说话,是<b>不说话的那些时刻</b>。
 *
 * <h2>它和 TTS 是两回事</h2>
 * 她说什么由 TTS 念(引擎自带那几条线)。这里放的是模型包自带的语音,跟着模型走
 * ——换成企鹅模型就是企鹅的声音。
 *
 * <h2>怎么绕开 EntityMaid</h2>
 * {@code MaidSoundInstance} 的构造器要真女仆实体,但它还有个按坐标播的兄弟
 * {@code MaidSoundInstanceAtPos},只收 x/y/z。而车万女仆挂在 {@code PlaySoundSourceEvent}
 * 上的钩子判的是<b>接口</b>({@code instanceof ICustomSoundBuffer})不是具体类,所以
 * 按坐标播的那个照样会被接管、把音频挂到声道上。一个女仆实体都不需要。
 *
 * <h2>反应音 vs 自主音——这条分法是这个类的骨架</h2>
 * <b>反应音</b>由事件驱动,她没有选择权:挨打就是挨打,不能"决定"喊疼。
 * <b>自主音</b>由她自己判断时机,而判断时机正是大模型擅长的事——尤其是
 * {@code environment/} 那一类(下雨了应一声、早上打个招呼),那是"陪伴"的质感所在。
 *
 * <p>不做"闲着就定时哼":循环播的固定音很快就从"有生气"变成"烦",而且需要一套
 * "她现在忙不忙"的判据。交给她自己判断,两个问题一起没有。
 */
public final class MaidVoice {

    /**
     * 自主音:她自己决定什么时候发。键是给模型看的名字,值是包里的声音 id。
     *
     * <p>没有 hurt / death / attack ——那些是<b>反应</b>不是选择,列在这里等于允许她
     * 无缘无故喊疼。它们走事件,见 {@link #onHurt}。
     */
    private static final Map<String, String> VOLUNTARY = new LinkedHashMap<>();
    static {
        VOLUNTARY.put("哼一声", "maid/mode/idle");
        VOLUNTARY.put("早晨", "maid/environment/morning");
        VOLUNTARY.put("夜晚", "maid/environment/night");
        VOLUNTARY.put("下雨", "maid/environment/rain");
        VOLUNTARY.put("下雪", "maid/environment/snow");
        VOLUNTARY.put("冷", "maid/environment/cold");
        VOLUNTARY.put("热", "maid/environment/hot");
        VOLUNTARY.put("种地", "maid/mode/farm");
        VOLUNTARY.put("喂食", "maid/mode/feed");
        VOLUNTARY.put("烧炉子", "maid/mode/furnace");
        VOLUNTARY.put("点火把", "maid/mode/torch");
        VOLUNTARY.put("捡到东西", "maid/ai/item_get");
        VOLUNTARY.put("亲昵", "maid/ai/tamed");
    }

    private MaidVoice() {}

    /** 这只同伴现在<b>能自己发</b>的那些(按模型包实际带的过滤)。 */
    public static Set<String> voluntary(UUID companion) {
        Set<String> have = available(companion);
        Set<String> out = new LinkedHashSet<>();
        VOLUNTARY.forEach((name, id) -> {
            if (have.contains(id)) out.add(name);
        });
        return out;
    }

    /** 按名字发一条自主音;这个包没带就返回 false。 */
    public static boolean speak(UUID companion, String name) {
        String id = VOLUNTARY.get(name);
        return id != null && play(companion, id);
    }

    /**
     * 挨打的反应。<b>按伤害来源挑</b>——烧着了和被人打是两种叫法,包里本来就分开录了。
     * 挑不到细分的就退回通用的 {@code hurt}。
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
        if (!play(companion, id)) play(companion, "maid/ai/hurt");   // 细分的没有就用通用的
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

    private static Set<String> available(UUID companion) {
        String pack = pack(companion);
        return pack == null ? Set.of() : Tlm.voices(pack);
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
