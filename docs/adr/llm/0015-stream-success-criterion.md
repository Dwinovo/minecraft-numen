# 0015 流算不算成功,只在一处判

- 状态:已接受
- 日期:2026-10-15
- 提交:`5e4c95126`、`870e0a2f7`

## 背景

三种协议各写各的流解析,只写了怎么收内容,收尾和出错全靠各自想起。结果三家各漏各的:Responses 的 `response.failed`、
Anthropic 的 `error` 事件、Chat 流中途的错误帧都被当成正常结束;连接半路断开也照收,玩家看到的是"空回应"或说一半的话,
工具调用拿到半截参数。

## 决定

- 判据只在 `StreamAccumulator.requireEnded()` 一处:流里带了错误,或协议约定的收尾没到,就抛 `LlmStreamException`
  (原因码 `llm.stream_failed`,带厂家原话)。三种协议收尾时第一步都过它。
- 各协议只负责认出自己的三样东西:错误帧、收尾事件、异常的结束原因。照 pi([0013](0013-pi-as-reference.md)):
  - Chat 必须有 `finish_reason`,`content_filter` 等异常取值算失败;
  - Anthropic 必须有 `message_stop` 和 stop_reason,`refusal`、`sensitive`、不认识的取值算失败,`pause_turn` 当正常结束;
  - Responses 必须有 `response.completed`,`response.failed` 与 `error` 帧算失败。
- `StreamFailureTest` 让三种协议跑同一套契约;加协议就把样本加进去。

## 后果

- 半路断开不再被当成完整回答;循环对失败统一重试一次。
- 不发 `finish_reason` 的非标准服务器会每轮失败;真遇到再照 pi 加一个只放行那家的兼容项。
