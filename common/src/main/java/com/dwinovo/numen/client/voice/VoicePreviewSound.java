package com.dwinovo.numen.client.voice;

import net.minecraft.client.resources.sounds.AbstractSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.AudioStream;
import net.minecraft.sounds.SoundSource;

import java.util.concurrent.CompletableFuture;

/**
 * 设置界面"试听"用的 2D 就地播放:不挂实体、无距离衰减、位置相对监听者
 * (即耳边直出,vanilla UI 音效同款做法)。数据同样是内存 PCM,经
 * {@code MixinSoundEngine} 的取数重定向进声音引擎——和 3D 路径共用一条
 * 管线,不另起播放机制。
 */
public final class VoicePreviewSound extends AbstractSoundInstance implements VoicePcmSource {

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
