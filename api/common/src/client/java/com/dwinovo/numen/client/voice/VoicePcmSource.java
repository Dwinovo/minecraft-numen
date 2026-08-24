package com.dwinovo.numen.client.voice;

import com.dwinovo.numen.Constants;
import net.minecraft.client.sounds.AudioStream;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;

import java.util.concurrent.CompletableFuture;

/**
 * "数据不来自 ogg 资源"的声音实例:取数被换成 {@link #openStream()}
 * 返回的内存 PCM 流。换法按 loader 分家(见
 * {@code com.dwinovo.numen.platform.services.IVoiceSoundFactory}):
 * Fabric 由 fabric 模块的 {@code MixinSoundEngine} 在 {@code SoundEngine.play}
 * 的流式取数处认这个接口;NeoForge 由子类覆写其补丁提供的官方
 * {@code SoundInstance.getStream} 钩子。两个 common 实现:
 * {@link EntityVoiceSound}(挂实体的 3D 空间音源)与
 * {@link VoicePreviewSound}(设置界面试听的 2D 就地播放)。
 */
public interface VoicePcmSource {

    /** 共用的 sounds.json 占位声音事件(见 {@link EntityVoiceSound} 的说明)。 */
    Identifier SOUND_LOCATION =
            Identifier.fromNamespaceAndPath(Constants.MOD_ID, "companion_voice");
    SoundEvent SOUND_EVENT = SoundEvent.createVariableRangeEvent(SOUND_LOCATION);

    /** 这句语音的数据源——语义对应更高 MC 版本 {@code SoundInstance#getStream} 官方钩子。 */
    CompletableFuture<AudioStream> openStream();
}
