# 模型接入:目录、协议与兼容项

状态:设计稿(10-14),用户 10-14 同意,第九节四项按建议定。第 1 至 5 步都已落地(第十一节写现状与差异);第 4 步(OpenAI 缺省走 Responses)照 pi-mono 逐项对齐后切换,**没有用真 key 验证过**。决策的来路见 `docs/adr/llm/`;这份写打算怎么改。

## 一、要解决的问题

1. **目录是手写的。** `ai/src/main/resources/numen_providers.json` 只有 15 家,每家几个型号与上下文长度,都会过时;
   能不能看图、输出上限、价格这些没有;玩家要接表里没有的型号,只能当成"自定义",能力一概不知。
2. **思考的写法按"家"定,表达不了"按型号定"。** 每家在目录里选一种 `thinkingFormat`(llm 0008)。
   可同一家不同型号控制方式不同(DeepSeek 新型号是开关 + 档位并存,Anthropic 新型号是自适应思考),只能写死或打补丁。
3. **厂家的差异散在三处:** 目录里的 `thinkingFormat` 与 `protocol`、`LlmProvider` 接口上的方言常量与 `defaultBaseUrl`、
   DeepSeek 和 Moonshot 两个子类(缓存计费、`reasoning_content` 回传)。加一家要知道该改哪处。
4. **Responses 从没接过真接口**(llm 0009),OpenAI 官方因此一直走 Chat Completions。现已照 pi 逐项对齐并切为缺省(未用真 key 验证)。
5. **OpenRouter 报 "Invalid key"**(已对外承诺 0.1.5 修),根因见第七节。

## 二、参照:pi-mono 的模型层(读过源码)

pi-mono 的 `packages/ai`(本地 `D:\01_Projects\pi-mono`)与我们要的几乎同构,而且它的型号目录就是从 models.dev 生成的
(`scripts/generate-models.ts`):

- **一个型号一条记录**(`src/types.ts` 的 `Model`):`id`、`name`、`api`(走哪种协议)、`provider`、`baseUrl`、`reasoning`、
  `thinkingLevelMap`(统一档位 → 这个型号的取值,`null` 表示不支持)、`input`(文字/图片)、`cost`、`contextWindow`、`maxTokens`、
  `headers`、`compat`(这种协议下的兼容项)。
- **协议由 models.dev 的 `npm` 字段推出:** `@ai-sdk/openai` → `openai-responses`,`@ai-sdk/anthropic` → `anthropic-messages`,
  其余(含 `@ai-sdk/openai-compatible`)→ `openai-completions`。
- **思考档位由 `reasoning_options` 推出**(`scripts/models-dev-reasoning-options.ts`):档位型直接映射到统一档位,不支持的记 `null`。
- **兼容项按协议分三套**,缺省按厂家与地址自动认出,个别可以覆盖。Chat 这一套里的 `thinkingFormat`(`openai`、`openrouter`、`deepseek`、
  `zai`、`qwen` 等)就是我们的"六族思考方言";还有 `maxTokensField`、`supportsDeveloperRole`、`supportsUsageInStreaming`、
  `requiresReasoningContentOnAssistantMessages`(DeepSeek、Kimi 要求回传 `reasoning_content`)等。
  Anthropic 这套有 `forceAdaptiveThinking`、`supportsTemperature`;Responses 这套有 `supportsDeveloperRole` 等。

我们照这个分法,只做我们要的三种协议,兼容项只收我们现在真有差异的那几项。

## 三、分三层

```
型号目录(数据)          协议(代码,三种)              兼容项(极少的代码知识)
models.dev 快照/缓存  →  Chat Completions            按协议各一组,缺省按厂家/地址认出,
+ 玩家手填               Responses                   自定义厂家可以覆盖
                        Anthropic Messages
```

### 1. 型号目录(`Catalog`,取代 `ProviderRegistry`)

**厂家:** `id`、名字、缺省地址、协议、鉴权方式、额外报头、兼容项。
**型号:** `id`、名字、厂家、协议(缺省随厂家)、上下文长度、输出上限、能不能看图、能不能调工具、能不能调温度、思考档位表、价格。

- **协议:** 按 `npm` 推(同 pi)。models.dev 里 OpenAI、Anthropic、xAI、Google 没写 `api` 地址(由 SDK 内置),我们补一张这几家的缺省地址;
  Google 原生协议我们不做,照旧走它的 OpenAI 兼容端点(`generativelanguage.googleapis.com/v1beta/openai`)。
- **来源与优先级(llm 0012):** 玩家手填 > 厂家自己的 `/models` 接口 > models.dev 缓存 > 随包快照。**按字段取最高的那一个,不合并、不猜。**
  - 随包快照:构建时从 `models.dev/api.json` 生成一份精简的 JSON(只留我们用到的字段)打进 `ai` 模块。
  - 运行时:客户端启动后后台拉一次 `api.json`,存 `config/numen/cache/models-dev.json`,一天最多刷一次;拉不到就用缓存或快照,不报错。
  - 厂家 `/models`:只用来列出 models.dev 没有的型号 id(例如本地的 Ollama、LM Studio、vLLM);能力仍按"不知道"处理。
- **列哪些厂家:** models.dev 有 228 家。界面上先列常用的一组(OpenAI、Anthropic、DeepSeek、OpenRouter、Kimi、智谱、通义、豆包、硅基流动、
  MiniMax、Google、xAI,国内外两个端点分开),其余可搜;本地部署(Ollama、LM Studio、vLLM)作为"自定义厂家"的模板。

### 2. 协议(`Api`,取代 `LlmProvider` 里与厂家相关的部分)

三种:`chat`(Chat Completions)、`responses`(OpenAI Responses)、`anthropic`(Anthropic Messages)。我们现有的
`OpenAIProvider`、`OpenAIResponsesProvider`、`AnthropicProvider` 就是这三种,改的是它们和外面的接口。

**中立的请求**(循环与会话只认它):系统提示、会话(`ConvoState.Msg`,经 `ProtocolView` 修成合法序列,llm 0011)、工具表(`IToolSpec`)、
选项(输出上限、温度、思考档位、缓存提示)。

**协议实现只做两件事:**
- 把中立请求 + 型号 + 兼容项编成一个 HTTP 请求(路径、鉴权头、请求体);
- 把流式返回解成中立事件:文字片段、思考片段、工具调用开始/参数片段/结束、用量、结束原因。最后得到 `AssistantTurn`。

**回传的私货:** `AssistantTurn` 里协议要求原样带回的东西(`reasoning_content`、思考签名、Responses 的加密思考 item)仍放在 `extras`,
出处(协议 + 厂家 + 型号)对不上就脱掉(llm 0010)。

传输(重试、退避、看门狗、取消,llm 0004)、用量四元与缓存记账(llm 0006)不变,用量字段的解读归各协议(Chat 协议把几家的缓存字段都认一遍:
`prompt_tokens_details.cached_tokens`、`prompt_cache_hit_tokens` 等)。

### 3. 兼容项(`Compat`,按协议各一组)

只收现在真有差异的几项:

| 协议 | 兼容项 | 取代现在的 |
|---|---|---|
| chat | `thinkingFormat`:思考参数怎么写(`openai` 顶层 `reasoning_effort` / `openrouter` 嵌套 `reasoning.effort` / `deepseek`、`zai` 写 `thinking.type` / `qwen` 写 `enable_thinking` / `none`) | 目录里的 `thinkingFormat` 与 `LlmProvider` 上的 `THINKING_*` 常量 |
| chat | `maxTokensField`(`max_tokens` 或 `max_completion_tokens`) | `applyGenerationParams` 写死的 `max_tokens` |
| chat | `replayReasoningContent`:回传时带上 `reasoning_content` | `DeepSeekProvider`、`MoonshotProvider` 两个子类 |
| chat | `streamUsage`:收不收 `stream_options.include_usage` | `applyStreaming` 的默认 |
| chat | `anthropicCacheControl`:请求里显式放 `cache_control` 断点(OpenRouter 转发给 `anthropic/*` 型号时) | 新增 |
| responses | `developerRole`:系统提示用 `developer` 还是 `system`(只对会思考的型号生效,其余一律 `system`) | 新增 |
| anthropic | `adaptiveThinking`:用 `thinking.type:adaptive` + `output_config.effort`,还是 `budget_tokens` | 目录里的 `budget` 形态 |
| anthropic | `temperature`:收不收温度 | 新增 |

**认出兼容项只在一处:** 按厂家 id 与地址推出缺省值的一个函数;自定义厂家在界面上可以改。

## 四、思考档位

- **统一档位:** 自动、关、低、中、高、最高。"自动"什么思考参数都不发,这条不变(llm 0008)。
- **每个型号支持哪几档,由目录里的思考档位表给出**(由 models.dev 的 `reasoning_options` 推出):
  - 档位型(`effort`,带取值表):只列取值表里有的档位;
  - 开关型(`toggle`):只有开、关;
  - 预算型(`budget_tokens`,带下限):档位映射成预算,预算表一张(待定,见第九节),不低于下限。
- **请求怎么写**由协议 + 兼容项的 `thinkingFormat` 决定。models.dev 只说"有开关",不说开关字段叫什么——这正是兼容项要管的。
- **查不到能力的型号**(自定义型号、`/models` 里列出来的)只给"自动"。

界面照旧只有一个下拉(llm 0008),选项随型号变。

## 五、档案与界面

- **档案**(`ProviderLibrary`,llm 0005)存:名字、厂家 id、型号 id、key、地址覆盖、代理、思考档位、上下文覆盖。能力一律从目录查。
- **选厂家 → 型号列表来自目录**,可搜;型号下面显示上下文、能不能看图、支持哪些思考档位。也可以直接手填型号 id。
- **自定义厂家:** 地址 + 协议(三选一)+ key,兼容项给缺省、可改;存进档案库,与内置厂家同等对待。
- **老档案迁移:** 一次性把老的厂家 id 映射到 models.dev 的 id(`moonshot`→`moonshotai`、`zhipu`→`zhipuai`、`dashscope`→`alibaba-cn`、
  `siliconflow`→`siliconflow-cn`、`gemini`→`google`、`grok`→`xai`;`ollama`、`lmstudio`、`vllm` 变成自定义厂家),映射表只用于迁移,迁完不再读。

## 六、OpenAI 默认走 Responses

models.dev 里 OpenAI 的 `npm` 是 `@ai-sdk/openai`,按 pi 的规则走 Responses,和其它厂家走同一条路(`Api.ofNpm`),没有按厂家 id 的协议特例。
请求体、条目形状、多轮回传、流事件、用量都已逐项对照 pi 的 `openai-responses.ts` 与 `openai-responses-shared.ts`(差异见第十一节"第 4 步")。
还没有用真 key 对话验证过:思考 item 回传、`developer` 角色、条目 id 配对、用量字段这几处要在真接口上确认。

## 七、OpenRouter "Invalid key"

查到的事实:
- 我们把**所有 401** 都显示成"密钥无效"(`LlmErrorWords`),厂家的原话没给玩家看。
- 用一个假的 OpenRouter key 实测:发到 OpenRouter 回 `401 User not found.`;发到 OpenAI 回 `401 invalid_api_key`;key 只有空白时回 `Missing Authentication header`。
- 我们从不去掉 key 首尾的空白。复制 key 时常带上空格或换行,发出去就是一个错的 key。

可能的根因有两个,都修:
1. **key 带空白:** 在档案保存与解析成 `LlmEndpoint` 处去掉首尾空白(只此一处)。
2. **请求发错了地方:** 报错时带上请求的主机名与厂家原话(例如"openrouter.ai 回复:User not found."),玩家与我们一眼看出是哪种。

报告者在 1.20.1,能复现最好;不能复现就以这两处修好为准,在 issue 里说明。

## 八、删掉什么

- `numen_providers.json` 与 `ProviderRegistry`(由目录取代;别名表只留迁移用的那张)。
- `DeepSeekProvider`、`MoonshotProvider`(由兼容项取代)。
- `LlmProvider` 上的 `THINKING_*` 常量、`defaultBaseUrl`、`pickProvider` 的分支(由目录与兼容项取代)。
- 目录里每个型号的手写 `temperature`/`maxTokens`(生成参数改由档案或兼容项给;要不要保留见第九节)。

## 九、已定(10-14 用户)

1. **厂家:** 界面先列常用的十几家(国内外端点分开),其余可搜。
2. **运行时拉 models.dev:** 默认开;拉不到静默用缓存或随包快照。
3. **预算型的档位映射:** 低 2048、中 8192、高 16384、最高 32768 token,不低于型号的下限。
4. **温度与输出上限:** 改到档案上设,不再按型号写在目录里。

## 十、分步

1. **目录:** 构建时生成快照、运行时缓存与刷新、`Catalog` 读口;老档案迁移。行为不变(同样的厂家、同样的请求)。
2. **协议与兼容项:** 三种协议改吃中立请求与兼容项;删两个子类与方言常量。用录下来的请求与流式返回做单测(每种协议、每种思考写法一组)。
3. **思考档位与界面:** 档位表、下拉随型号变、型号能力展示、自定义厂家。
4. **Responses 设为 OpenAI 缺省:** 照 pi 逐项对齐后切换;真接口验证待用 OpenAI key 做。
5. **OpenRouter:** key 去空白、报错带主机与原话。

每步:构建、单测、日常 GameTest;第 2、4 步要真接口跑一次(DeepSeek、OpenAI、Anthropic、OpenRouter 各一次,用你的 key,不跑 bench)。

## 十一、落地情况(第 1、2、3、5 步)

**目录**(`ai` 的 `agent.catalog`)
- 随包快照 `ai/src/main/resources/numen_models_snapshot.json`:models.dev 全部 228 家的裁剪版(2.6 MB,一家一行),没有再按“常用”裁——
  常用只是 `Catalog.popular()` 的一份标记,界面第 3 步用。手动更新:`./gradlew :ai:updateModelsSnapshot`(可 `-PmodelsProxy=host:port`),普通构建不联网。
- 运行时缓存 `config/numen/cache/models-dev.json`:`ProviderLibrary.refreshCatalog()` 在客户端启动时后台刷新,一天最多一次,拉不到静默。
- 来源是 `CatalogSource`(缓存 `DownloadCache`、快照 `BundledSnapshot`),只读自己那份;优先级只在 `Catalog` 的构造顺序里,整条记录取最高来源的。
  厂家 `/models` 这一级还没做:以后作为一个 `CatalogSource` 插在缓存之前。玩家手填的值在档案里,由 `EndpointResolver` 盖在目录结果之上。
- 档案 `ConnectionProfile` + 目录 → `LlmEndpoint`,只在 `EndpointResolver` 一处。请求、压缩阈值、界面都取这个结果。

**协议与兼容项**(`agent.provider`)
- 三个协议实现由 `LlmProvider.of(compat)` 组装;编请求只吃 `ModelSpec`(型号与档位表)、`RequestOptions`(档案里的温度、输出上限、思考档位)和各自的兼容项。
- 兼容项缺省的认出与档案覆盖项只在 `Compats.resolve` 一处;思考档位表是 `ThinkingTable`,预算映射表只在那里。

**与上文不一致的地方**
- `adaptiveThinking` 的缺省取决于**型号**(只有 effort 取值、没有预算写法),所以 `Compats.resolve` 多收一个档位表。
- Chat 的 `thinkingFormat`:Z.ai、智谱、Kimi、火山方舟同形(只写 `thinking.type`),都归 `zai`;不再为 xAI 特判,有没有档位由型号的档位表决定。
- 回合出处戳改成 `协议/厂家/型号`(以前是适配器类名/型号,同一协议的不同厂家分不开)。老存档里的戳对不上,升级后老回合的私货会被脱掉一次。
- OpenAI 官方按 `npm` 走 Responses(第 4 步),`VendorDefaults` 里不再有协议覆盖。Responses 请求不附带 `stream_options`。
- 老档案迁移在 `ProviderLibrary` 的读时升级:档案库是 store 的小文档,第 2 版起厂家 id 是 models.dev 的 id,版本号就是“已迁过”的判据(`lmstudio` 既是老 id 又是 models.dev 的 id,只看 id 分不出);更早的 `models_dev_ids` 标记当第 2 版认。
- 密钥去首尾空白在 `LlmEndpoint` 的构造里,是全仓唯一一处;报错带主机与厂家原话在 `LlmHttpException`;失败原因是 agent 的原因码(`Cause`),界面按语言由 `CauseWords` 说。

**思考档位与界面**(第 3 步)
- "这一档在这个型号、这个协议与兼容项下实际写成什么"只在 `ThinkingPlan`(`agent.provider`)算一次:请求体、界面列哪几档、每档右边的说明都问它。
  发不出参数的档位不列;只剩"自动"时界面不出思考一栏。
- 照 pi:OpenRouter 对推理型号一律写 `reasoning.effort`(目录只给预算或开关的,缺省给低、中、高),关写 `none`;Anthropic 的关写 `thinking.type=disabled`。
- 已弃用(`status: deprecated`)的型号在读目录时丢掉,界面不显示也不提示;档案里用着的老型号按手填处理。快照里仍留着它们。
- 型号能力多留输出上限与输入模态,界面叫法照 pi 与 models.dev:上下文、最大输出、思考、多模态、工具调用。
- 界面照定稿草图:厂家、型号用窗口中央的选择框 `PickerBox`(搜索在顶,型号带规格列);短列表就地下拉,按可见区域摆放。

**与上文不一致的地方(第 3 步)**
- 第四节说预算型"不低于下限";另外还去掉不小于型号输出上限的档(预算算在输出里,发出去必被拒)。
- 第五节说型号下面显示"能不能看图";改为输入模态(图像、PDF 等),跟 models.dev 一致。

**第 4 步:Responses 对齐 pi 与切为 OpenAI 缺省**

对照 pi 的 `packages/ai/src/api/openai-responses.ts`、`openai-responses-shared.ts`。逐项:一致的不写,不一致的写成"pi / 原来 / 现在"。

**请求**
- 系统提示:pi 放进 input,`{role, content: 字符串}`,推理型号且兼容项不禁才用 `developer`,否则 `system`。原来不分型号一律 `developer`、内容是 `input_text` 列表并带 `type:"message"`。现在照 pi;"会不会思考"取自型号档位表(`ThinkingTable.thinks()`),`buildSystemMessage` 因此从协议接口里撤掉,角色只在 Responses 自己一处定。
- 用户消息:pi 是 `{role:"user", content:[input_text]}`,没有 `type`。原来多一个 `type:"message"`。现在照 pi。
- 助手回合:pi 回传 `{type:"message", role:"assistant", content:[output_text+annotations], status:"completed", id, phase}` 和 `function_call`(带原 `fc_` id、`call_id`),思考 item 原样在最前。原来 message 与 function_call 都不带 item id。现在把流里 `output_item.done` 的 message/function_call id(及 message 的 `phase`)记进 extras 的 `numen_item_ids`,回传时写回;没记下 id 的回合(换过模型、私货被脱掉)message 用确定的占位 id、function_call 不带 id,与 pi 对不同模型回合的处理一致。服务端靠这些 id 校验思考与后一条输出的配对,原来"带 rs_ 的思考 + 不带 id 的调用"是 pi 从不发的组合。
- 思考 item 的存法:原来 `mergeExtraJson` 遇到非数组就覆盖,一次回应里有两条思考时只剩最后一条;现在每条都包成数组追加。
- 工具调用参数:pi 把解析后的参数再序列化;空参数写 `{}`。原来原样回传空串。现在空白写 `{}`。
- 工具结果:pi 的 `output` 是字符串,空时写 `(no tool output)`。原来可能发空串。现在照 pi。
- 工具表:一致(扁平 `{type, name, description, parameters}`,默认不开 `strict`)。原来多发 `parallel_tool_calls:true`(接口缺省就是 true),已去掉。
- `reasoning`/`include`:pi 选了力度才写 `reasoning:{effort, summary:"auto"}` 加 `include:["reasoning.encrypted_content"]`;选了"关"只写 `{effort:"none"}`,不要 include。原来只写 effort、不要摘要,`include` 总是发(不会思考的型号不收这个 include)。现在照 pi。"自动"(我们有、pi 没有的档)不写 `reasoning`,但会思考的型号仍要 include——服务端默认也会思考,store:false 下带 id 却没有加密内容的思考 item 回传会被拒。
- `store:false`、`stream:true`:一致。不用 `previous_response_id`:一致。
- 输出上限:pi 对 `max_output_tokens` 取不低于 16(接口拒绝更小的)。原来原样发。现在照 pi。
- 温度:pi 设了就发。原来只发 `>0`,0 被丢。现在设了就发(Chat 本来如此);推理型号 models.dev 里 `temperature:false`,由 `RequestOptions.temperatureFor` 挡掉,思考时不发的判据只此一处。
- 没有对齐、也不打算做的 pi 兼容项:`sessionAffinityFormat`、`supportsLongCacheRetention`、`supportsExplicitPromptCacheMode`(`prompt_cache_key`、会话报头、24h 缓存:依赖会话 id,目前不传,缓存靠前缀自动命中)、`supportsStrictMode`、`supportsOpenAIGrammarTools`、`supportsToolSearch`(我们没有对应能力)、`serviceTier`、`toolChoice`。我们的 `ResponsesCompat` 只有 `developerRole`(对应 pi 的 `supportsDeveloperRole`),没有 pi 没有的项。

**流事件**
- `output_text.delta`、`reasoning_summary_text.delta`、`reasoning_text.delta`、`output_item.added/done`、`function_call_arguments.delta`:一致。
- `refusal.delta` 与 message 里的 `refusal` 内容:pi 当文本收。原来无视(拒绝时回合是空的)。现在收。
- 摘要分段:pi 在 `reasoning_summary_part.done` 补 `

`。原来直接拼接。现在在下一段 `part.added` 时补(同样的分隔,结尾不留空行)。
- `function_call_arguments.done` 与 `output_item.done` 里的最终参数:pi 用它们覆盖累积值,`added` 里的初始参数也收。原来只拼增量。现在照 pi。
- `response.failed`:pi 抛 `code: message`(无 error 时 `incomplete: reason`,再无则 "Unknown error")。原来当作正常收尾,回合变成"空回应"。现在是流失败,原话原样带出。
- `error` 帧:pi 抛 `Error Code <code>: <message>`。原来无视。现在同。
- 没有收尾事件(completed/incomplete/failed 都没来)就断流:pi 抛错。原来把半截回应当成功。现在同。
- 以上失败走新异常 `LlmStreamException` → 原因码 `llm.stream_failed`(`Cause`),界面显示"回应中途断了:原话";与 HTTP 层的 `LlmHttpException` 分开,因为它没有状态码和主机。循环对所有失败都重试一次,这类也一样。
- `reasoning_summary` 在 `output_item.done` 里整段覆盖累积摘要(pi):没有对齐——我们的摘要是整条流的一个字符串,增量到齐即完整。Azure 的"加密内容只在 completed 里给"的补写(pi 为 Azure 做的)也没做:Azure 在 models.dev 里不是 `@ai-sdk/openai`。

**用量**
- pi:`input = input_tokens − cached_tokens − cache_write_tokens`,`cacheRead = cached_tokens`,`cacheWrite = cache_write_tokens`,另记 `reasoning_tokens`。原来没有 `cache_write_tokens`。现在补上。`reasoning_tokens` 已含在 output 里,我们的四元没有它的位置,不单列(llm 0006)。
- 日志里的"tokens=…"原来读 Chat 的字段名,Responses 与 Anthropic 都显示 0/0;现在读归一后的四元。

**切换**
- `VendorDefaults` 里"OpenAI 走 Chat"的协议覆盖整张表删除,`ModelsDev` 直接用 `Api.ofNpm`。
- 档案里已选 OpenAI 的不用动,协议每次从目录取。回合出处戳从 `chat/openai/…` 变成 `responses/openai/…`,升级后老回合的私货脱掉一次。

**真接口上最可能出问题的地方**(用真 key 测时重点看)
1. 助手回合回传时 `message`/`function_call` 的 id 与思考 item 的配对校验(报错形如 "was provided without its required following item" / "Item … not found")。
2. "自动"档带 `include` 但不带 `reasoning`,服务端是否仍返回带加密内容的思考 item。
3. `developer` 角色与 `summary:"auto"` 在非官方的 Responses 兼容端点上是否被接受(兼容项 `developer_role` 可关)。
4. `gpt-5.x` 的 `phase`(commentary / final_answer)在含工具调用的多轮里是否需要按原顺序交错回传——我们把一个回合的文本合成一条 message,放在工具调用之前。
5. 缓存命中:没有传 `prompt_cache_key`,看 `cached_tokens` 是否仍随前缀稳定增长。

**流失败与前缀缓存(照 pi 把三种协议对齐)**

*流怎样算失败*(pi:`anthropic-messages.ts`、`openai-completions.ts`、`openai-responses-shared.ts`)
- 判据只有一处:`StreamAccumulator.requireEnded()`,三种协议的 `finalizeStream` 都先过它——流里带了错误(`failure`),或协议约定的收尾没来(`ended` 为假),就抛 `LlmStreamException`(原因码 `llm.stream_failed`)。协议只做三件事:认出自己的错误帧、认出拒绝类的收尾原因、认出自己的收尾事件。厂家错误话的形状(`{"error":{"message"}}` 等)与 HTTP 错误体共用 `ProviderReply.of`。
- Anthropic:pi 把 `event: error` 当错误抛出,见了 `message_start` 却没有 `message_stop` 抛 "ended before message_stop",没有 stop_reason 抛 "ended without a stop reason",`refusal`(取 `stop_details.explanation`)、`sensitive`、不认识的取值都算错误,`pause_turn` 按正常结束。原来全无处理(注释写"error 帧由传输层处理",传输层其实只认 `data:`),错误帧被无视、断流被当成成功。现在照 pi。
- Chat:pi 靠 OpenAI SDK 在错误帧上抛错,`finish_reason` 必须有("Stream ended without finish_reason"),`content_filter`、`network_error` 与不认识的取值算错误。原来错误帧被无视(`choices` 缺失就返回)、没有 `finish_reason` 的半截回应照收、异常的 `finish_reason` 也照收。现在照 pi;pi 另有一个兼容项允许厂家不发 `finish_reason`,我们没有这个项,真有厂家不发时再加。
- 为什么会漏:三个协议各写各的 `accumulateChunk`,"这个流算不算成功"没有单一出口,每家只写了"怎么收内容",收尾与出错靠各自想起;而 `NumenLlmClient` 对累积器抛的异常只记日志,没有第二道关。所以现在把判据收到累积器里,新协议要么设 `ended`/`failure`,要么 `finalizeStream` 过不了。`StreamFailureTest` 对三种协议跑同一套契约(正常样本能收尾、收尾前断流失败、错误帧失败并带出原话),新协议把样本和错误帧加进去就受同样约束。
- 老测试里"只喂内容碎片就收尾"的几处,补上了 `finish_reason` / `message_stop` 帧,它们测的是碎片累积,不是流怎么收场。

*前缀缓存*
- 前缀稳定性:每轮请求里在历史之前的东西——工具表(`ToolRegistry` 是 `LinkedHashMap`,四个工具的描述与 schema 都是静态文字)、系统提示(各层都是登记序或排好序的稳定内容,时间、坐标、背包等会变的东西都在每轮挂的 `<runtime_state>` 里,不在前缀里)——没有发现每轮都变、却排在前面的内容。`CachePrefixTest` 对三种协议断言:同一段对话多走一步,工具表、系统提示、已有消息逐字不变(忽略断点标记)。
- Anthropic:pi 在 system(一个,OAuth 时两个)、最后一个工具、最后一条用户消息的末块各放一个断点;`cacheRetention` 缺省 `short`(5 分钟,`cache_control` 不带 ttl),`long` 时写 `ttl:"1h"`,没有额外报头;`none` 不放断点。我们放四个:最后一个工具、system、消息里最后两个可放的块(跳过思考块)。出入只在消息里多放一个——每轮请求末尾都会再追加一条现算、不进历史的 `<runtime_state>`,pi 的"最后一块"下一轮就变了、白写一次缓存;倒数第二块是历史末尾,下一轮回看时命中。这个理由写进 `CacheBreakpoints`,Anthropic 与下面的 Chat 共用。我们没有 `cacheRetention`:没有人提出需求,不加选项,一律 5 分钟;若要加,1h 的缓存写是 2 倍价,值得先看真实间隔再定。思考块进历史后是否影响缓存是服务端行为(换思考参数会让消息段缓存失效),pi 与我们都是原样回传。
- Responses:pi 传 `prompt_cache_key`(会话 id,最长 64 字符),`long` 时再传 `prompt_cache_retention:"24h"`,并按 `sessionAffinityFormat` 写 `session_id`/`x-client-request-id`(OpenRouter 是 `x-session-id`)报头;`supportsExplicitPromptCacheMode` 是 `none` 时关隐式缓存。我们有稳定的会话标识——同伴 UUID(也是对话日志的归属),所以 `chatStreaming` 多收一个 `sessionId`(服务端大脑的 `LlmMind` 传同伴的 UUID,设置页探测与评测传 null),经 `buildRequestBody` 到 Responses 写成 `prompt_cache_key`;Chat 与 Anthropic 不用它(靠前缀缓存)。没有做:24h 保留与显式缓存模式(没有对应需求)、会话报头(传输层的报头是整个端点共用的,按请求改要动传输层,而 OpenAI 官方只认 `prompt_cache_key`)。
- Chat:pi 对 OpenRouter 上 `anthropic/*` 型号(`cacheControlFormat:"anthropic"`)在 system、最后一个工具、最后一条会话消息的末个文字块放 `cache_control`;DeepSeek、Kimi 等自动缓存家什么也不放。原来我们两样都没有,OpenRouter 上的 Claude 完全没有缓存。现在 `ChatCompat.anthropicCacheControl`(`Compats.resolve` 一处认出:OpenRouter 且型号以 `anthropic/` 开头,档案可覆盖)为真时放断点,位置与 Anthropic 一致(`ChatCacheControl`)。pi 只在 `api.openai.com` 上给 Chat 传 `prompt_cache_key`;OpenAI 官方现在走 Responses,Chat 不再到那里,没有照做。
- 用量:
  - Chat:pi 取 `prompt_tokens_details.cached_tokens ?? prompt_cache_hit_tokens` 为读、`cache_write_tokens` 为写、`input = prompt − 读 − 写`,并认 Moonshot 放在 `choice.usage` 里的用量。我们的读写字段本来就认(另认 DeepSeek 的未命中字段、顶层 `cached_tokens`);原来不认 `choice.usage`,Moonshot 整轮用量丢失,现在认。`cache_creation_tokens` 是 Anthropic 归一化的私货,不再有人写,删掉。
  - Anthropic:pi 的 `input_tokens` 不含缓存,读写各单列,`message_delta` 里只收非 null 的字段。原来把两截用量归一成 OpenAI 的字段名(`prompt_tokens` 等)再由默认实现拆回来,delta 里带 null 的 `input_tokens` 会抛异常、带了 `input_tokens` 却没带缓存字段会把缓存清零。现在覆写 `usage()`,累积器里按字段合并原始用量,没有归一这一步。
  - Responses:读写口径上一节已对齐。
  - `NumenLlmClient` 的日志也改读归一后的四元。
