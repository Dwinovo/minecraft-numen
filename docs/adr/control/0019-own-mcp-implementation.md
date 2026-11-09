# 0019 MCP 自己实现、自己维护:主版本 2025-11-25,只实现用到的协议面

- 状态:已接受,已落地
- 日期:2026-11-09
- 取代:[0013](0013-mcp-on-upstream-libraries.md)
- 另见:`docs/input-pipeline.md` §五,`mcp/build.gradle` 头注,`licenses/THIRD-PARTY.txt`;先例 llm [0001](../llm/0001-zero-dependency-stack.md)(模型接入零依赖自写)

## 背景

0013 把 MCP 的服务端与客户端建在官方 Java SDK(2.1.0)上:服务端是 SDK 的 Servlet 传输挂在内嵌 Tomcat 上,客户端是 `McpSyncClient`。
本意是"协议升级跟着库走",实际付出的代价:

- **撞包与打包**:SDK 的 JSON 走 Jackson 3,注解仍是 Jackson 2 的包;Minecraft 1.21.4–1.21.10 自带 Jackson 2.13,整合包与 Paper 插件
  常带别的版本。为此把 SDK、Jackson 3、jackson-annotations、YAML 数据格式、networknt schema 校验、snakeyaml-engine 一起改包名合成一份 jar
  (2ca1f9d5f),合成时多版本目录(3df383af3)、同名服务文件(182a6ddd7)、Multi-Release 清单(b24ca1dce,Forge 47–49 读这种 jar 起不来)
  逐个踩坑;Reactor、Tomcat 再按坐标嵌套。Fabric、NeoForge、Paper 三套平铺与嵌套各写一份。
- **运行时类加载**:SDK 靠 `ServiceLoader` 按线程上下文类加载器找 JSON 映射器,Paper 上找不到,只好显式构造交给它(9d7bed217);Tomcat 给上下文
  另建应用类加载器,它起的线程(Reactor 的工作线程)一直带着,Forge 47 的事件总线按上下文加载器找类时找不到,只好自写一个 `Loader`(5f83fdf99)。
  它还要往 JVM 登记全局 URL 流处理器工厂,和游戏加载器的撞,得先关掉。
- **协议行为受制于 SDK**:服务端问人只能借一次进行中的 `tools/call` 的流,没有在飞的调用就排队等下一次,问出去收不回;认人的过滤器读走请求体后
  要原样重放给 SDK;会话号要从答复头里抠出来告诉在场记录;"一批增删只发一次 list_changed" 要在 SDK 的传输外面再包一层拦截。
  客户端在会话 404 时无条件重开会话、重新握手,哪怕我们已经关了它——测试只好先等自己的请求收场再断开(b131af74e),并数着在飞的 HTTP 请求。
- **体积**:为一个只用两成功能的协议库,每个加载器 jar 带上 SDK、Jackson 3、Tomcat、Reactor。

MCP 规范本身不大,我们用到的面更小,而且已有自写 `ai` 传输的先例(llm 0001:零依赖、自写、自测)。

## 决定

1. **自己实现、自己维护 MCP**,彻底去掉上游 SDK、Jackson、Tomcat、Reactor、networknt、snakeyaml-engine。`mcp` 仍是零 Minecraft、零 Numen API
   回引的纯 JVM 模块,打包照旧平铺进 Numen API;numen-api 只认 `mcp` 自己的类型。
2. **版本**:主版本 2025-11-25。照规范协商:对方要的版本我们支持(2025-11-25、2025-06-18、2025-03-26)就用它,否则答最新的;客户端只接受这三个。
   各版本线格式的差异逐条对过 `schema.ts` 与 `basic/transports.mdx`,只在 `ProtocolVersion` 一处回答(2025-03-26 可成批、没有 title 与
   elicitation;2025-06-18 起有 title、表单式 elicitation,单选写 `enum`+`enumNames`;2025-11-25 起有 icons、`Implementation.description`、
   elicitation 的 `mode` 与 `form`/`url` 能力,单选写 `oneOf` 的 `const`/`title`)。2026-07-28(无状态)这次不做:传输、会话与协议核心是分开的组件,
   将来加它不必动核心。
3. **只实现用到的协议面,用到的完整正确**:
   - 服务端:initialize/initialized、ping、tools/list、tools/call(注解、icons、serverInfo 的 title/description/icons、instructions)、进度
     (客户端带了 progressToken 才发)、`notifications/tools/list_changed`、`elicitation/create`(表单)、`notifications/cancelled`(双向)、
     会话(`Mcp-Session-Id`、DELETE、结束了或不存在的答 404、没带的答 400)、`MCP-Protocol-Version` 头(不支持的答 400)、GET 的 SSE 流
     (一个会话一条,第二条 409)、POST 答一个 JSON 或一条 SSE 流。资源、提示词、补全、采样、日志、roots、tasks 不做,能力里不声明;
     SSE 的断点续传(事件编号)服务端不做。
   - 客户端:Streamable HTTP(SSE 答复、会话号、版本头、DELETE 收尾;流断了带 `Last-Event-ID` 接上、照 `retry` 等;会话 404 照规范重新握手)
     与 stdio 子进程;initialize 协商;tools/list 翻页;tools/call;list_changed 后重列;401/403 交 OAuth 补救再重发(传输里的一步);
     超时的请求发 `notifications/cancelled` 撤回。不声明 elicitation 等客户端能力,服务端发来的这类请求答"没有这个方法"(与之前一致)。
   - 不做 JSON Schema 校验:SDK 的校验器在服务端只用来检查我们自己登记的 inputSchema 本身是不是合法 schema(参数校验我们关了),在客户端只用来按
     对方的 outputSchema 校验 structuredContent(规范是 SHOULD;我们把结果交给模型读,不据此做事)。参数的检查只在宿主一处(工具自己),不重复。
4. **结构:组合,一个概念一处**:
   - `rpc`:JSON-RPC 2.0 的一端 `RpcPeer`(编号、在途请求、对方的请求与撤回、超时撤回),服务端会话与客户端连接共用;消息与编解码 `RpcMessage`
     (Gson,编号统一成 long 或字符串);SSE 的写与读 `Sse`。
   - `protocol`:MCP 的类型是普通 record(`Tool`、`Content`、`Implementation`……),换算只在 `McpCodec`;版本与差异只在 `ProtocolVersion`;
     方法名只在 `McpMethods`。
   - `server`:`McpEndpoint` 只拼装与起停。处理链由独立的环节组合(`Stage`):`OriginGuard`(Origin/Host)→ `BearerAuth`(这是谁)→
     `StrangerThrottle`(陌生地址)→ `StreamableHttp`(传输)。会话状态只在 `ServerSession`/`SessionRegistry`,"连着没有"汇总在
     `McpPresence`;MCP 方法在 `ServerMethods`(限流 `McpLimits`、审计 `McpAudit` 照旧);工具表按令牌在 `ToolTable`,变了一次通知一次;
     问人在 `Questions`(走 GET 流,撤回发 `notifications/cancelled`)。HTTP 用 JDK 自带的 `com.sun.net.httpserver`,地址与端口取自
     `mcp_server.json`,端口被占用抛出写明端口与去处,不换端口。
   - `client`:传输是窄接口 `ClientTransport` 的两个实现(`HttpClientTransport` 用 `java.net.http`,`StdioClientTransport`);会话在
     `McpClient`;接进来的服务器那一层(请求头优先级、OAuth、重列、内容块成文字、Windows 的 `cmd /c`)在 `McpLink`;连接表 `McpConnections` 照旧。
5. **线程模型**:服务端每个 HTTP 请求一条线程,它是那条流唯一的写者;请求的处理在另一条线程上(调工具占着它等宿主),结果经队列交回来写,
   游戏主线程报进度、问人只入队不碰套接字。HTTP 分派线程从守护线程上起(随之是守护线程)。停服先结束所有会话(流收场),再关监听,最后打断
   还在等宿主的处理线程并等它们散完。客户端请求各在一条线程上送出与读答复,GET 流一条线程读,对方的请求各在一条线程上答。GameTest 每刻的等待点
   照旧是确定性的计数:在场记录里收尾中的会话(`McpPresence.closing`)、在等游戏的请求(`GameWaits`)、问出去未答的问题(`asking`),外加用例客户端
   自己在等答复的请求数(`McpClient.pending`)。线程种类只在 `McpThreads` 一处决定(用虚拟线程,都是守护的),别处只说起一条带名字的线程
   或每个任务一条线程。
6. **打包**:第三方只剩客户端 OAuth 用的 Nimbus 一族(1.21.4–1.21.10 的游戏经微软登录库 msal4j 自带旧版,别的版本上整合包里的模组、Paper 插件也可能带别的版本),仍改包名合成一份 jar(`relocatedNimbus`)平铺进三平台的 jar;
   不再有嵌套的库。

## 后果

- 去掉 SDK、Jackson 3、Tomcat、Reactor 等十几个库与它们的重定位、嵌套、类加载器绕路;三平台的打包只剩一份 Nimbus 平铺;许可清单只剩 Nimbus。
- 0013 里因 SDK 才有的限制随之消失:问人走 GET 流,不必借调用的流,问出去能撤回;请求体只读一次;会话号由我们自己开;工具表变化一批一次是
  `ToolTable` 本身的行为;客户端关了就是关了。
- 规范每改一版要我们自己追:读 changelog 与 schema,在 `ProtocolVersion`/`McpCodec` 里加差异。正确性靠官方一致性测试
  (`@modelcontextprotocol/conformance` 的 server 与 client 场景)、官方 Inspector 与 everything 服务器互通、以及本模块的单测守住;
  不实现的协议面在一致性测试里是"不适用",实现了的必须全过。
- 将来要做 2026-07-28 无状态版,加一个传输与会话的实现,协议核心不动。
