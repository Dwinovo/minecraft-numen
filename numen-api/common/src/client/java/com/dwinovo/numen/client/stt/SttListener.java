package com.dwinovo.numen.client.stt;

/**
 * 转写结果回调。流式后端边说边回 {@link #onPartial}，批量后端只在结尾回
 * {@link #onFinal}——上层(麦克风按钮 → 输入框)只需对这两个回调作出反应，
 * 无需知道底下是哪种范式。所有回调可能在非主线程触发，UI 侧需自行切回主线程。
 */
public interface SttListener {

    /** 流式增量转写(可多次)。批量后端不调用。 */
    default void onPartial(String text) {}

    /** 最终转写文本。批量与流式都在结尾调用一次。 */
    void onFinal(String text);

    /** 转写失败。 */
    void onError(Throwable error);
}
