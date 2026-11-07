# 0001 不用官方 SDK,自己写一层零依赖的接入

- 状态:已接受
- 日期:2026-05-15
- 提交:`a6bf70d87`(引入 SDK)、`ab2cf21c3`(撤掉)、`b3cbfd833`

## 背景

先试了 OpenAI 官方 Java SDK(4.35.0),用 Shadow 把它和 18 个传递依赖(OkHttp、Okio、Jackson、Kotlin 标准库……)打进模组并改包名:
- 模组 jar 涨到每个加载器约 47 MB,而 SDK 的功能只用了两成左右;
- SDK 死守原版 OpenAI 的格式:兼容厂家加的字段(DeepSeek 的 `reasoning_content` 等)在往返中被悄悄丢掉,多轮工具对话直接坏掉。

## 决定

不带任何第三方 HTTP 或模型 SDK:
- 网络用 JDK 自带的 `java.net.http.HttpClient`(异步、HTTP/2);
- JSON 用 Minecraft 自带的 Gson;
- 线格式由我们自己的适配器翻译(llm 0002)。

(东方小女仆在生产环境走的也是这条路。)

## 后果

- 发行包回到正常大小,没有依赖冲突。
- 每种协议的请求、流式解析、重试都要自己写、自己测(llm 0004、0009)。
- 厂家的私有字段能原样保留,这是选这条路的主要收益。
