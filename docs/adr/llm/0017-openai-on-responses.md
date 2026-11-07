# 0017 OpenAI 官方缺省走 Responses,协议一律按目录推

- 状态:已接受;照 pi 对齐,未用真 key 在真接口上验证
- 日期:2026-10-15
- 修订:[0009](0009-three-protocols.md)
- 提交:`75feeec2d`、`c1f7df6ab`

## 背景

0009 写好了 Responses,但从没接过真接口,OpenAI 官方一直按厂家 id 特例走 Chat Completions。models.dev 里 OpenAI 的 `npm`
是 `@ai-sdk/openai`,按 0012 的规则本该走 Responses;OpenAI 自己也推荐它,思考内容只有它能原样回传。用户定为照主流、参考 pi,不等真 key。

## 决定

- 删掉按厂家 id 的协议特例,协议一律由 models.dev 的 `npm` 推出;OpenAI 官方因此走 Responses。
- 切之前把 Responses 的请求、多轮回传(思考 item 与消息、工具调用的条目 id)、流事件、用量逐项对照 pi 改齐([0013](0013-pi-as-reference.md))。

## 后果

- 换模型的回合出处戳从 `chat/openai/…` 变成 `responses/openai/…`,升级后老回合的私货会被脱掉一次(0010)。
- 第一次用真 key 要盯:条目 id 的配对("required following item""Item … not found")、"自动"档要求返回加密思考、
  第三方 Responses 端点对 `developer` 角色与 `summary` 的接受度。
