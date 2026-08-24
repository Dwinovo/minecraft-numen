package com.dwinovo.numen.client.voice;

import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.sounds.AudioStream;
import net.minecraft.client.sounds.SoundBufferLibrary;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Forge 侧的同伴 3D 语音:Forge 1.20.1 的补丁把 {@code SoundEngine.play} 的取数
 * 改成了调用 {@code SoundInstance.getStream(SoundBufferLibrary, Sound, boolean)}
 * 补丁钩子(字节码里 vanilla 形状的 {@code SoundBufferLibrary.getStream} INVOKE
 * 已不存在,@Redirect 扫不到目标会掀桌)——覆写该钩子返回 {@link #openStream()}
 * 即可,零 mixin。
 */
public final class ForgeEntityVoiceSound extends EntityVoiceSound {

    public ForgeEntityVoiceSound(UUID entityUuid, AbstractClientPlayer body, PcmAudio audio, float volume) {
        super(entityUuid, body, audio, volume);
    }

    @Override
    public CompletableFuture<AudioStream> getStream(SoundBufferLibrary loader, Sound sound, boolean looping) {
        return openStream();
    }
}
