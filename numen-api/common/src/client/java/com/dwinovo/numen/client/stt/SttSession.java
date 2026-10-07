package com.dwinovo.numen.client.stt;

/**
 * 一次录音转写会话。麦克风按下 → {@link SttBackend#open} 开会话，采集层把 PCM
 * 块喂进 {@link #feed}，松开 → {@link #finish}。批量实现缓冲 PCM、finish 时打包
 * 上传；流式实现 feed 时即发、结束帧收尾。{@link #cancel} 中途放弃(不出结果)。
 */
public interface SttSession {

    /** 喂一块采集到的 PCM(格式见 {@link SttAudio#FORMAT})。 */
    void feed(byte[] pcm);

    /** 录音结束：批量后端在此打包上传并出结果,流式后端发结束帧收尾。 */
    void finish();

    /** 中途放弃:不再出结果、释放资源。 */
    void cancel();
}
