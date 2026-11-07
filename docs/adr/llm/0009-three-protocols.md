# 0009 三种线协议:Chat Completions、Anthropic Messages、OpenAI Responses

- 状态:已接受;被 [0017](0017-openai-on-responses.md) 修订
- 日期:2026-08-02(Anthropic)、09-20(Responses)
- 提交:`8d34c7acb`、`bc4302389`、`77a15013b`

## 背景

OpenAI 兼容的 Chat Completions 覆盖了大多数厂家,但 Anthropic 的 Messages 是另一套格式;OpenAI 自己的新接口 Responses 也不同,
而且思考内容要以 item 的形式原样带回下一轮。

## 决定

- **协议是目录里的一个字段** `protocol`(缺省 Chat Completions,`anthropic`、`responses`),按它选适配器;
  Anthropic 兼容的站点(智谱、Kimi、MiniMax 的 Claude 端点)只是数据里的一行。
- **Anthropic Messages(08-02):** 与 Chat 适配器同级、从零实现:
  - 顶层 system;
  - 角色严格交替,相邻消息归并,并行的工具结果并成一块;
  - 工具的 `input_schema` 平铺;
  - 思考块带签名回传,缺签名整块不发;
  - 按块的流事件累积;
  - 用量两截归一成同一套字段名;
  - stop_reason 统一映射。
  
  协议差异收成三个接口钩子:`authHeaders`(`x-api-key` 对 Bearer)、`chatPath`(`/messages` 对 `/chat/completions`)、
  `applyStreaming`(严格校验未知字段的协议不收 `stream_options`)。
- **OpenAI Responses(09-20,照 pi-mono):**
  - `store:false` 加 `include: reasoning.encrypted_content`,不用 `previous_response_id`;
  - 思考 item 原样回传;
  - 不发 item id,避开 `fc_*`/`rs_*` 的配对校验;
  - 一条回合在请求里展开成"思考 + 文本 + 每个工具调用"几个 item(接口改为 `assistantToRequestItems`)。

## 后果

- 三种协议共用循环、会话、重试与用量记账,差异都在适配器里。
- Responses 只有单测,第一次真用要盯思考 item 回传、`developer` 角色、工具表的扁平形状、用量字段。
- 目录里没有一家默认走 Responses,OpenAI 官方仍走 Chat Completions。
