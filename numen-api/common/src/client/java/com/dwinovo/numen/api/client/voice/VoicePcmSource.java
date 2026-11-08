package com.dwinovo.numen.api.client.voice;

import com.dwinovo.numen.api.Constants;
import net.minecraft.client.sounds.AudioStream;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;

import java.util.concurrent.CompletableFuture;

/**
 * "数据不来自 ogg 资源"的声音实例:取数被换成 {@link #openStream()}
 * 返回的内存 PCM 流。换法按 loader 分家(见
 * {@code com.dwinovo.numen.api.platform.services.IVoiceSoundFactory}):
 * Forge 由子类覆写其补丁提供的 {@code SoundInstance.getStream} 钩子,Fabric 由子类覆写
 * Fabric API 的 {@code FabricSoundInstance.getAudioStream} 钩子。两个 common 实现:
 * {@link EntityVoiceSound}(挂实体的 3D 空间音源)与
 * {@link VoicePreviewSound}(设置界面试听的 2D 就地播放)。
 */
public interface VoicePcmSource {

    /** 共用的 sounds.json 占位声音事件(见 {@link EntityVoiceSound} 的说明)。 */
    ResourceLocation SOUND_LOCATION =
            new ResourceLocation(Constants.MOD_ID, "companion_voice");
    SoundEvent SOUND_EVENT = SoundEvent.createVariableRangeEvent(SOUND_LOCATION);

    /** 这句语音的数据源——语义对应更高 MC 版本 {@code SoundInstance#getStream} 官方钩子。 */
    CompletableFuture<AudioStream> openStream();
}
