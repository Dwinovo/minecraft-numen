package com.dwinovo.numen.client.voice;

import com.dwinovo.numen.client.agent.ClientNumenLookup;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.AudioStream;
import net.minecraft.sounds.SoundSource;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * 从同伴身体位置播出的一句合成语音——一个挂在声音引擎上的
 * 空间音源（3D、距离衰减、跟随实体移动）。
 *
 * <h2>技术选型：走 SoundEngine,不直连 OpenAL</h2>
 * 把任意 PCM 播成空间音源有两条路：
 * <ol>
 *   <li><b>自定义 {@link SoundInstance} + 在引擎取数处换成自己的
 *       {@link AudioStream}</b>（本实现）。引擎解析 {@code sounds.json}
 *       找到声音事件后,流式声音的数据源就是"取流"这一步,把它换成
 *       {@link #openStream()} 即可。声道分配、STREAMING 池、每帧位置更新、
 *       线性距离衰减、音量分类（VOICE 滑条）、暂停/恢复、设备热切换
 *       全部由引擎接管。</li>
 *   <li><b>绕开引擎,用 {@code com.mojang.blaze3d.audio.Library/Channel}
 *       自建 OpenAL 源</b>。可以做到,但要自己管理声道生命周期、监听器
 *       变换、音量选项、暂停语义和音频设备重载,而且 {@code SoundEngine}
 *       的声道池是私有的,拿到它同样绕不开访问拓宽——脆、代码量大好几倍。</li>
 * </ol>
 *
 * <h2>取数按 loader 分家（{@code Services.VOICE} 工厂）</h2>
 * "换取数"这一步两侧机制不同,所以实例经
 * {@link com.dwinovo.numen.platform.services.IVoiceSoundFactory} 创建：
 * <ul>
 *   <li><b>NeoForge</b> — 其 1.21.1 补丁已提前引入后续 MC 版本的
 *       {@code SoundInstance.getStream(SoundBufferLibrary, Sound, boolean)}
 *       官方钩子,{@code SoundEngine.play} 直接调它。工厂返回覆写了该钩子的
 *       {@code NeoEntityVoiceSound},零 mixin——这已是官方钩子的最终形态;</li>
 *   <li><b>Fabric</b> — 运行 vanilla 字节码,取数仍是
 *       {@code SoundBufferLibrary.getStream(ResourceLocation, boolean)} 调用,
 *       由 fabric 模块的 {@code MixinSoundEngine} @Redirect 到
 *       {@link #openStream()},工厂返回本类。vanilla 正式引入官方钩子的
 *       版本再删该 mixin 改覆写。</li>
 * </ul>
 * 代价是每句话一个 SoundInstance（句间有 ≤1 tick 的接缝,语音上不可闻）,
 * 换来全部基础设施免费——值。
 *
 * <h2>sounds.json 占位</h2>
 * 引擎播放前要 {@code resolve()} 到一个 {@code sounds.json} 声音事件,
 * 否则直接拒播。资源里注册了 {@code numen_api:companion_voice},指向一个
 * <b>永远不会被读取</b>的原版 ogg（{@code minecraft:random/click},
 * 仅为通过资源存在性校验）并标记 {@code "stream": true}——stream 标记
 * 决定引擎走 streaming 路径,从而命中上面的取数替换。
 *
 * <h2>实体跟随</h2>
 * 每 tick 把音源坐标同步到同伴当前位置;身体解析不到（换维度重建的
 * 瞬间）先按 UUID 重找,持续找不到（死亡/卸载）则停播。
 */
public class EntityVoiceSound extends AbstractTickableSoundInstance implements VoicePcmSource {

    private final UUID entityUuid;
    private final PcmAudio audio;
    private AbstractClientPlayer body;

    /** 经 {@code Services.VOICE} 工厂创建(loader 子类需要,故 public)。 */
    public EntityVoiceSound(UUID entityUuid, AbstractClientPlayer body, PcmAudio audio, float volume) {
        super(VoicePcmSource.SOUND_EVENT, SoundSource.VOICE, SoundInstance.createUnseededRandom());
        this.entityUuid = entityUuid;
        this.body = body;
        this.audio = audio;
        this.volume = volume;
        this.looping = false;
        this.delay = 0;
        this.relative = false;
        this.attenuation = SoundInstance.Attenuation.LINEAR;
        moveToBody(body);
    }

    @Override
    public void tick() {
        if (body == null || body.isRemoved()) {
            body = ClientNumenLookup.resolve(entityUuid);
        }
        if (body == null) {
            stop();
            return;
        }
        moveToBody(body);
    }

    private void moveToBody(AbstractClientPlayer b) {
        this.x = b.getX();
        this.y = b.getEyeY();
        this.z = b.getZ();
    }

    /**
     * 本句语音的数据源——数据就在手里,完全绕过 ogg 资源加载。
     * Fabric 由 fabric 模块的 {@code MixinSoundEngine} 在取数处调用;
     * NeoForge 由 {@code NeoEntityVoiceSound} 覆写的官方 {@code getStream}
     * 补丁钩子调用。
     */
    @Override
    public CompletableFuture<AudioStream> openStream() {
        return CompletableFuture.completedFuture(new PcmAudioStream(audio));
    }
}
