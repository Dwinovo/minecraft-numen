package com.dwinovo.numen.client.voice;

import net.minecraft.client.resources.sounds.AbstractSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.AudioStream;
import net.minecraft.sounds.SoundSource;

import java.util.concurrent.CompletableFuture;

/**
 * 设置界面"试听"用的 2D 就地播放:不挂实体、无距离衰减、位置相对监听者
 * (即耳边直出,vanilla UI 音效同款做法)。数据同样是内存 PCM,取数与 3D
 * 路径共用一套 loader 分家机制(经 {@code ClientServices.VOICE} 工厂创建:
 * NeoForge 返回覆写官方 {@code getStream} 补丁钩子的 {@code NeoVoicePreviewSound},
 * Fabric 走本类 + fabric 模块的 {@code MixinSoundEngine} 取数重定向),
 * 不另起播放机制。详见 {@link EntityVoiceSound} 的选型说明。
 */
public class VoicePreviewSound extends AbstractSoundInstance implements VoicePcmSource {

    private final PcmAudio audio;

    public VoicePreviewSound(PcmAudio audio, float volume) {
        super(VoicePcmSource.SOUND_EVENT, SoundSource.VOICE, SoundInstance.createUnseededRandom());
        this.audio = audio;
        this.volume = VoiceLibrary.clampVolume(volume);
        this.looping = false;
        this.delay = 0;
        this.relative = true;                                   // 坐标相对监听者
        this.attenuation = SoundInstance.Attenuation.NONE;      // 不做距离衰减
        this.x = 0.0;
        this.y = 0.0;
        this.z = 0.0;
    }

    @Override
    public CompletableFuture<AudioStream> openStream() {
        return CompletableFuture.completedFuture(new PcmAudioStream(audio));
    }
}
