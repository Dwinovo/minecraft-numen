# 0013 MCP 端点与客户端建在官方库上,独立成纯 JVM 模块 `mcp`

- 状态:已接受;在分支上已做完
- 日期:2026-10-20
- 另见:`docs/input-pipeline.md` §五,`licenses/THIRD-PARTY.txt`

## 背景

手写的 MCP 服务端(JDK HttpServer、自写会话与 GET 流)、客户端(自写传输与 JSON-RPC)和 OAuth 只覆盖我们用到的那部分协议,
协议每改一版(会话、进度、征询、授权发现、客户端注册)就要自己追。

## 决定

- **服务端用官方 MCP Java SDK**(`HttpServletStreamableServerTransportProvider`),跑在内嵌 Tomcat 上:SDK 的 Streamable HTTP
  传输是 Servlet 形态,Tomcat 是它的主流宿主,不必自己适配异步 Servlet。我们只在 Servlet 前挂一层过滤器:Origin 检查、Bearer 令牌、
  限流、在线状态、审计。每个令牌一台 SDK 服务器,会话不会跨令牌。
- **客户端用 SDK 的 `McpSyncClient`**(HTTP 与 stdio 传输),授权失败交给我们的 OAuth。
- **OAuth 通用部分用 Nimbus `oauth2-oidc-sdk`**(授权请求、PKCE、令牌请求、刷新、动态注册、响应解析含 `iss`);
  MCP 特有的步骤自己写在 `mcp`:受保护资源元数据发现(`WWW-Authenticate` 的 `resource_metadata`,否则 `/.well-known`)、
  授权服务器元数据按规范顺序探测、注册优先级(配置的 client_id → Client ID Metadata Document → 动态注册 → 明确报错)、
  `resource`(RFC 8707)、403 `insufficient_scope` 补授权。令牌按授权服务器分存进钥匙串。
- **分层**:`mcp` 是纯 JVM,只认抽象接口(`McpHost`、`McpCaller`、`CredentialStore`);numen-api 依赖它,反过来不行。
  与游戏有关的(`ExternalHands`、`ServerActuator`、`McpMode`、`ExternalHost`、`ExternalBrain`)留在 numen-api。
- **打包**:`mcp` 与第三方库随 Fabric(`include`)、NeoForge(`jarJar`)与 Paper 插件的 jar 一起发;slf4j、gson、asm 用游戏自带的。

## 后果

- 删掉自写的传输、会话与 OAuth 大量代码,换成 SDK 与 Nimbus;协议升级跟着库走。
- jar 变大(第三方库嵌套进去)。
- SDK 服务端有状态:服务器主动征询(权限同意)要借一次进行中的工具调用的通道,没有在飞的调用就排队等下一次;征询发出后无法撤回。
