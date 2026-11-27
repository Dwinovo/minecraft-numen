# 0010 对话存在服务端,格式照 Claude Code 的会话记录;目标是对话里的一行

- 状态:已接受,已落地
- 日期:2026-11-28
- 修订:[0008](0008-brain-on-owners-client.md)(上下文全在主人客户端)、[0002](0002-storage-files-three-layers.md)(对话日志照 pi 的格式)
- 另见:control 0028(提问工具照 Claude Code)、control 0029(系统提示照 Claude Code)

## 背景

- 现在一只同伴一份 `chat.jsonl`,放在她的创建者的电脑上(`config/numen/client/companions/<她>/chat.jsonl`),目标另存 `goal.json`。面板看一个会话,
  是客户端把几个成员的本地日志现拼出来的;单聊没拼上,面板就空了(`Conversations#membersOf` 只认存过的群)。
- 往后对话要能换人接着聊(记录书,物品另起一份 ADR),群里会有好几个真玩家:同一份记录要被不同电脑上的人读到,一个成员不在线时群里的话也要有地方
  先存着。存在某个人的电脑上做不到,QQ、Discord、Grok Bot 都存在服务端。
- 用户:"完全对齐 grok 和 claude 的机制,群聊、流对齐 grok,存储格式、goal 之类的对齐 claude";"这一轮先把聊天记录放服务端去存","群聊先不考虑太复杂机制"。
- 长期目标照 Claude Code 的 `/goal`(11-27 实测其会话记录):状态就是会话里的 `goal_status` 行;不防死循环,"条件不合理是玩家或上游的事,不兜底"。

## 决定

心智模型:**对话是服务端的一份记录,脑子在主人的客户端上读它、往后写它。** 记录只往后追加;一段对话同一时刻只有一个写的人,就是坐着她座位的那个客户端。
脑子仍在主人客户端(0008 不变):key、模型、调模型、她的收件箱都不动。

1. **存在哪。** `<存档>/numen/conversations/<会话 uuid>.jsonl`,一段对话一个文件,文件名就是会话 id。目录按内容叫,不认识载体(以后的书、指令、界面都只是
   指着某个会话 id)。服务端在她的登记项里记着她**当前的会话 id**;清空上下文就是换一个新的会话 id(照 Claude Code 的 `/clear` 开新会话),旧文件留着。
2. **格式照 Claude Code 的会话记录。** 一行一条 JSON,没有文件头,只追加。每行有 `type`、`uuid`、`parentUuid`(串成链)、`timestamp`、`version`(Numen 的版本)。
   - `user`:有人说话(`message.content` 是字符串),或工具结果(`content` 是 `tool_result` 块,行上带 `sourceToolAssistantUUID`);元信息的那几条带 `isMeta: true`;
     被打断写一条内容是 `[Request interrupted by user]` 的 `user` 行。
   - `assistant`:她的回复,`message` 照 Anthropic 的消息(`id`、`model`、`stop_reason`、`content` 是 `text`/`thinking`/`tool_use` 块);照 Claude Code
     一个块一行,同一条回复共用 `message.id`。接 OpenAI 协议的厂家在发出前转换,记录里只有这一种写法。调模型出错照 Claude Code 写一条 `isApiErrorMessage: true` 的
     `assistant` 行;面板要翻成玩家语言的,另带结构化的 `code` 与 `args`。
   - `attachment`:往后追加的提醒,`attachment.type` 分种类:`goal_status`、世界上的事、换了人设、目录变了等。发给模型时渲染成 `<system-reminder>`。
   - `system`:`subtype: compact_boundary` 是压缩边界(`parentUuid` 为空,`logicalParentUuid` 指回压缩前最后一条,`compactMetadata` 记触发方式与前后用量),
     紧跟一条 `isCompactSummary: true` 的 `user` 行是摘要;上下文从最后一个边界起重放。
   - 我们加的字段只有两个,照 Grok Bot:`user` 行的 `from`(谁说的,`player@短码` 或同伴的身份)、每行的 `conv`(这一行属于哪个群,单聊为空)。
     Claude Code 自己的字段(`cwd`、`gitBranch`、`entrypoint`、文件快照、权限决定)不要。
   - 认不得的 `type` 读时跳过、留在文件里;插件自己的记录是 `<命名空间>:<名字>` 类型的行,不进上下文也不进面板。
   - 换模型、换思考档位不再单独记:模型名在每条 `assistant` 的 `message.model` 里。
3. **谁能写、谁能读。** 写:只有坐着她座位的客户端(0008 的座位只认创建者),经座位追加,服务端按到达顺序落盘。读:她的主人表上的人(0006)能翻页、能订阅新行。
   一个写的人,所以没有冲突合并,也不加锁。
4. **怎么同步。** 座位上加三件事(名字定稿以代码为准):
   - 开座位时取她当前会话**最后一个压缩边界起**的全部行,脑子在内存里拼上下文;压缩过的老行不传。
   - 脑子每写成一行就沿座位追加;连接有序,不另做确认协议。断线时脑子本来就停(0008),最多丢掉在飞的半轮。
   - 面板从服务端翻页、订阅服务端推来的新行。主人自己的面板也走这条:落了盘的才是记录,不从本机脑子另取一份。正在吐字的那一段只是脑子给本机面板的实时显示,
     不进记录,成形之后以服务端推来的那一行为准。
5. **群,这一轮不改机制。** 仍是现在的样子:每个成员的行写在她自己的会话里,带 `conv`;"这个群发生了什么"由服务端把各成员的行按 `conv` 归并(现有的 `History`
   搬到服务端)。群自己的公共记录、Grok 式的叫醒与 `SendMessage` 以后另定。
6. **目标是对话里的一行,照 Claude Code 的 `/goal`。**
   - 设定、清除各写一条 `sentinel: true` 的 `goal_status`(`condition`、`met`);当前目标就是文件里最后一条 `goal_status`(压缩不影响,文件从不删改)。
   - 设定时追加一条 `isMeta` 的 `user` 行,原文照 Claude Code:
     `A session-scoped Stop hook is now active with condition: "<条件>". Briefly acknowledge the goal, then immediately start (or continue) working toward it — treat the condition itself as your directive and do not pause to ask the user what to do. The hook will block stopping until the condition holds. It auto-clears once the condition is met — do not tell the user to run `/goal clear` after success; that's only for clearing a goal early.`
     其中 `/goal clear` 换成 Numen 里清除目标的说法。
   - 她每停一次评估一次(时机沿用现在的:身体手上还有会结束的活、收件箱里还排着会叫醒她的条目时先不判):结果写一条 `goal_status`(`met`、`reason`);没达成再追加一条 `isMeta` 的 `user` 行
     `Stop hook feedback:\n[<条件>]: <理由>`,接着跑;达成就清掉。评估员那次模型调用不进记录。
   - 删掉:`goal.json`、轮数上限(`MAX_GOAL_TURNS`)、"卡住"判定与连卡收工(`STUCK`、`STUCK_STREAK_TO_GIVE_UP`)。主人按停止**不**清目标(Claude Code 的打断不清)。
7. **不迁移。** 主人电脑上的旧 `chat.jsonl`、`goal.json` 不再读,也不替玩家删;读旧格式、升级旧版本的代码一并删掉(beta)。
8. **这一轮不动的**:她的收件箱、用量、定时、札记仍在主人客户端;物品(记录书等)、上下文分层重排、群聊机制另起。

## 后果

- 服主看得到她的对话(文件在服务器上)。这和"书能交给别人"是同一件事;常见问题里要写明。key 仍只在主人电脑上。
- 换一台电脑、同一个服,她的对话还在;换一个服,对话留在那个世界里。跨世界的东西(key、偏好,以后的全局 AGENTS.md)在主人客户端。
- 面板只有一个来源,单聊没历史那一类 bug 没了生根的地方。
- 目标跟着对话走:以后对话能换人接着聊,目标一起过去。

## 实现说明

座位上的方法(名字以 `SeatMethods` 为准):

| 方向 | 方法 | 干什么 |
|---|---|---|
| 客户端 → 服务端 | `conversation/open` | 请求,要坐着她的座位。答复:她当前的会话 id、读成上下文要的行(按重放的顺序)、物理上最后一行、当前目标从设定起的 `goal_status` 行 |
| | `conversation/append` | 通知,只有坐着她座位的连接能写,而且只写她此刻在写的那份会话;按到达的顺序落盘。不对的丢弃并记一行 |
| | `conversation/new` | 通知,清空上下文:新会话的 id 由客户端起,服务端记进她的登记项并通知订阅的人;旧文件留着 |
| | `conversation/page` | 请求,翻一页;她的主人表上的人都能翻,不要求坐着座位。单聊给 `companion`,群给会话印 `tag`,游标不透明 |
| | `conversation/watch` | 请求,订阅;主人表上的人都能订阅 |
| | `conversation/read` | 请求,当前会话的原文,导出记忆用,要坐着座位 |
| 服务端 → 客户端 | `conversation/rows` | 通知,订阅了的会话里新落盘的行,带此刻的会话 id;`rows` 为空是换了新会话 |

和 ADR 正文不一样的地方:

- **整理时原文保留的近段**照 Claude Code 的 `compactMetadata.preservedSegment`(`headUuid`、`tailUuid`)指着它们原来的行,不抄第二份;`conversation/open` 答复的行按"边界、摘要、原文保留的那一段、整理之后写下的行"排,所以取的不是单纯的"最后一个边界起的全部行"。
- **切断**:主人叫停写成内容是 `[Request interrupted by user]` 的 `user` 行;别的切断(死亡、登出、被接管、这一轮没能发出请求)写成 `isApiErrorMessage` 的 `assistant` 行,带 `code` 与 `args`,文字块是给模型的那句话。
- **加的字段**除 `from`、`conv` 外,一条助手回复的第一行还带 `origin`(哪家模型产的)与 `extras`(服务商要原样回塞的私货);思考签名在思考块自己的 `signature` 里。没有它们换了协议的服务商回塞思考时就认不出出处。`from` 是说话人的身份(`player@短码`),随收件箱的条目记着(条目 JSON 的 `speaker`),`<query>` 上的 `from` 属性与记录里的 `from` 都从它来,不从文字里读回;主人的话每一句都带说话人,单个主人也一样。一条 `user` 消息只放一个人的话:几个人的话在同一批里各写一条相接的 `user` 行,世界上的事并进第一条。
- **attachment 只用了** `goal_status` 与 `persona_changed`,两者都不渲染进模型的上下文(世界上的事仍然从收件箱走,在 `user` 消息里,上下文分层这一轮不动)。
- **目标**:设定时的指示与评估员的反馈各是一条 `isMeta` 的 `user` 行,经收件箱的 `goal_directive` 与 `goal` 两种条目交给她,`inject` 把它们各写成独立的一行;`goal` 仍是接续投递。`goal_status` 行 `met` 为真的评估行本身就是"做完了",不再多写清除行。目标跟着会话:清空上下文换了新会话,目标不跟过去;主人按停止与别的切断都不清目标。
- **清空她的记忆**不再删对话记录:对话换一个新会话,旧的一份留在服务器上,札记与目标照旧清掉。
- **面板只读服务端**:群的归并(`History`)在服务端做,只读各成员此刻的那份会话;清空之后面板只看得到新会话,旧会话里的群聊记录不再出现在面板上。单聊没有历史的那类问题随之没有了:单聊的成员就是她一个。
- **导出记忆**的 zip 里对话记录叫 `conversation.jsonl`,是向服务端要来的原文。
- 行的 `uuid`、`parentUuid`、`timestamp` 由客户端的写手盖,服务端只按到达顺序追加,不改写。
