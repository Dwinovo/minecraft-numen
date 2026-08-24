package com.dwinovo.numen.platform;

import com.dwinovo.numen.client.voice.EntityVoiceSound;
import com.dwinovo.numen.client.voice.ForgeEntityVoiceSound;
import com.dwinovo.numen.client.voice.ForgeVoicePreviewSound;
import com.dwinovo.numen.client.voice.PcmAudio;
import com.dwinovo.numen.client.voice.VoicePreviewSound;
import com.dwinovo.numen.platform.services.IVoiceSoundFactory;
import net.minecraft.client.player.AbstractClientPlayer;

import java.util.UUID;

/**
 * Forge 侧的语音声音工厂:Forge 1.20.1 的补丁把 {@code SoundEngine.play} 的
 * 取数改成调用 {@code SoundInstance.getStream} 补丁钩子(vanilla 形状的
 * {@code SoundBufferLibrary.getStream} INVOKE 在其运行时字节码里不存在,
 * @Redirect 会 0 目标掀桌),所以返回覆写了该钩子的子类,零 mixin。
 */
public class ForgeVoiceSoundFactory implements IVoiceSoundFactory {

    @Override
    public EntityVoiceSound entityVoice(UUID entityUuid, AbstractClientPlayer body,
                                        PcmAudio audio, float volume) {
        return new ForgeEntityVoiceSound(entityUuid, body, audio, volume);
    }

    @Override
    public VoicePreviewSound previewVoice(PcmAudio audio, float volume) {
        return new ForgeVoicePreviewSound(audio, volume);
    }
}
