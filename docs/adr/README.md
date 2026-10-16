# 架构决策记录(ADR)

每条记录写一个决定:当时的背景、定了什么、带来什么后果。格式照 Michael Nygard 的
[Documenting Architecture Decisions](https://cognitect.com/blog/2011/11/15/documenting-architecture-decisions)(2011)。

- 按领域分目录,每个目录各自编号,编号不复用。跨目录引用写目录名加编号,如"body 0001"。
- 记录定下以后不改内容;决定变了就写一条新的,旧的状态改成"被 NNNN 取代"或"被 NNNN 修订",两条互相链接。
- 状态:已接受、被取代、被修订;定了还没做的写"已接受,未落地"。
- 现状与细节看各自的设计稿(`docs/pathing.md`、`docs/shell.md`、`docs/input-pipeline.md` 等);ADR 只记为什么这样定。

按关心的问题分,不按模块分:一个决定常横跨几个模块,模块还会改名、拆分。想按模块查,看最后一列。

| 领域 | 目录 | 一句话 | 主要涉及的模块 |
|---|---|---|---|
| 身体 | [body/](body/README.md) | 她为什么是一个服务端玩家,怎么让她动、怎么看 | `numen-api`(身体、输入层)、`numen` |
| 操控 | [control/](control/README.md) | AI 怎么操控她:从注册工具、命令行到 Lua | `agent`、`lua`、`numen-api`(程序、外部大脑) |
| 运行形态 | [runtime/](runtime/README.md) | 她在哪一端跑、数据存哪、两端怎么传 | `store`、`agent`、`numen-api`(网络、归属) |
| 寻路 | [pathing/](pathing/README.md) | 从第一版 A* 到 0.1.5 | `numen:pathing` |
| 模型接入 | [llm/](llm/README.md) | 怎么接各家模型:零依赖自写、线格式适配、三种协议、思考方言、档案 | `ai` |
