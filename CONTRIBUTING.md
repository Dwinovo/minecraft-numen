# 参与贡献 / Contributing

欢迎提 PR。只有两个请求：
PRs are welcome. Just two asks:

- **改动尽量小**，一个 PR 只做一件事。/ **Keep the change as small as possible**, one thing per PR.
- **清楚自己改了什么。** 用 AI 写没关系。/ **Know what you changed.** Using AI is fine.

## 分支 / Branches

每个 Minecraft 版本一条分支，分支名就是版本号，默认分支 `1.21.1` 功能最新。PR 提到你改的那个版本的分支就行，移植到其它版本由维护者来做。
Each Minecraft version has its own branch named after the version; the default branch `1.21.1` has the newest features. Target the branch of the version you changed; the maintainer ports it to other versions.

## 构建与测试 / Build and test

`1.20.1`、`1.20.2`、`1.20.4` 分支把 `neoforge` 换成 `forge`。
On `1.20.1`, `1.20.2` and `1.20.4`, replace `neoforge` with `forge`.

```bash
./gradlew :numen:fabric:build :numen:neoforge:build
./gradlew :ai:test :script:test :agent:test :numen-api:common:test :numen:common:test
./gradlew :numen:neoforge:runGameTestServer
# 端到端:起一台真的专用服,用原版客户端协议走一遍玩家能做的事(要先跑过 datagenAll,约 2 分钟,不进 CI)
# End to end: starts a real dedicated server and drives it with a vanilla-protocol client (run datagenAll first; ~2 min, not in CI)
./gradlew :e2e:e2eTest
# Paper 端(第一次要下载 Paper 服务端;都不进 CI)
# Paper side (the first run downloads the Paper server; neither is in CI)
./gradlew :e2e:e2eTest -Pe2eServer=paper      # 同一批端到端场景对着 Paper 服,约 4 分钟 / the same scenarios against a Paper server, ~4 min
./gradlew :numen:paper:runGameTestServer      # 同一批 GameTest 在 Paper 上跑,按 20 刻每秒,约 35 分钟 / the same GameTests on Paper, real time at 20 TPS, ~35 min
```

## 授权 / License

代码贡献按 LGPL-3.0 授权。插件和兼容模组只要单独发布、通过 API 使用 Numen，可以采用任何协议，包括闭源。
Code contributions are licensed under LGPL-3.0. Plugins and compatibility mods distributed separately that use Numen through its API may use any license, including proprietary.
