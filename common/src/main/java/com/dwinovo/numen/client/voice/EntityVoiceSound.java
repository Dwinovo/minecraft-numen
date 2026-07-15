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
 *       找到声音事件后,流式声音的数据源是一个
 *       {@code SoundBufferLibrary.getStream} 调用;更高版本的 MC 在
 *       {@code SoundInstance} 上有公开的 {@code getStream} default 方法
 *       可直接覆写,<b>1.21.1 还没有这个口子</b>,所以由一个极小的
 *       {@code @Redirect} mixin（{@code MixinSoundEngine}）在
 *       {@code SoundEngine.play} 里把这次调用转到 {@link #openStream()}——
 *       语义与后续版本的官方钩子完全一致,升版本时可以无缝替换掉 mixin。
 *       声道分配、STREAMING 池、每帧位置更新、线性距离衰减、音量分类
 *       （VOICE 滑条）、暂停/恢复、设备热切换全部由引擎接管,
 *       跨 Fabric/NeoForge 共用一份 common 代码。</li>
 *   <li><b>绕开引擎,用 {@code com.mojang.blaze3d.audio.Library/Channel}
 *       自建 OpenAL 源</b>。可以做到,但要自己管理声道生命周期、监听器
 *       变换、音量选项、暂停语义和音频设备重载,而且 {@code SoundEngine}
 *       的声道池是私有的,拿到它同样绕不开访问拓宽——脆、代码量大好几倍。</li>
 * </ol>
 * 方案 1 的代价是每句话一个 SoundInstance（句间有 ≤1 tick 的接缝,
 * 语音上不可闻）加一个单点 mixin,换来全部基础设施免费——选它。
 *
 * <h2>sounds.json 占位</h2>
 * 引擎播放前要 {@code resolve()} 到一个 {@code sounds.json} 声音事件,
 * 否则直接拒播。资源里注册了 {@code numen_api:companion_voice},指向一个
 * <b>永远不会被读取</b>的原版 ogg（{@code minecraft:random/click},
 * 仅为通过资源存在性校验）并标记 {@code "stream": true}——stream 标记
 * 决定引擎走 streaming 路径,从而命中 mixin 的取数重定向。
 *
 * <h2>实体跟随</h2>
 * 每 tick 把音源坐标同步到同伴当前位置;身体解析不到（换维度重建的
 * 瞬间）先按 UUID 重找,持续找不到（死亡/卸载）则停播。
 */
public final class EntityVoiceSound extends AbstractTickableSoundInstance implements VoicePcmSource {

    private final UUID entityUuid;
    private final PcmAudio audio;
    private AbstractClientPlayer body;

    EntityVoiceSound(UUID entityUuid, AbstractClientPlayer body, PcmAudio audio, float volume) {
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
     * 由 {@code MixinSoundEngine} 在 {@code SoundEngine.play} 的流式取数处调用,
     * 对应更高 MC 版本 {@code SoundInstance#getStream} 官方钩子的语义。
     */
    @Override
    public CompletableFuture<AudioStream> openStream() {
        return CompletableFuture.completedFuture(new PcmAudioStream(audio));
    }
}
