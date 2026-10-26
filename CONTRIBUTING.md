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
./gradlew :numen:fabric:runGameTestServer
./gradlew :plugins:tlm:runGameTestServer      # 车万女仆联动的那一批,单开一次跑批 / the Touhou Little Maid integration cases, a run of their own
./gradlew :plugins:ysm:gametest:runGameTestServer  # YSM 联动的那一批,同样单开一次跑批 / the Yes Steve Model integration cases, also a run of their own
./gradlew :plugins:ftbquests:runGameTestServer  # FTB Quests 联动的那一批,同样单开一次跑批 / the FTB Quests integration cases, also a run of their own
# 以上几条 GameTest(要先跑过 datagenAll)不跟着推送跑,在 GitHub 的 Actions 页手动触发 "GameTest",各平台并行。测试服务器每刻开头等寻路与程序的后台线程停下再往下走,
# 结果不看机器快慢。/ The GameTest runs above (run datagenAll first) do not run on push; trigger "GameTest" by hand on the Actions page, platforms in parallel. The test server waits at the start of every tick for the pathing and program background threads to settle, so results do not depend on machine speed.
# 端到端:起一台真的专用服,用原版客户端协议走一遍玩家能做的事(要先跑过 datagenAll,约 2 分钟)
# End to end: starts a real dedicated server and drives it with a vanilla-protocol client (run datagenAll first; ~2 min)
./gradlew :numen:neoforge:e2eTest
# Paper 端(第一次要下载 Paper 服务端)
# Paper side (the first run downloads the Paper server)
./gradlew :numen:paper:e2eTest                # 同一批端到端场景对着 Paper 服,约 4 分钟 / the same scenarios against a Paper server, ~4 min
./gradlew :numen:paper:runGameTestServer      # 同一批 GameTest 在 Paper 上跑,按 20 刻每秒、用每条用例自己写的时限,至少半小时 / the same GameTests on Paper, real time at 20 TPS with each case's own time limit, 30+ min
# 端到端与 Paper 的 GameTest 时间长,不跟着推送跑;在 GitHub 的 Actions 页手动触发 "Slow tests"。
# The end-to-end runs and the Paper GameTest are long and do not run on push; trigger "Slow tests" by hand on the Actions page.
# 排查用的两个开关,加在 runGameTestServer 命令后面(Paper)/ two switches for investigating, appended to the Paper runGameTestServer command:
#   -PgametestOnly='<完整用例名的正则>'   只跑名字匹配的用例;正则要匹配完整名 numen.<类名小写>.<用例名>,如 'numen\.braingametests\..*',写 'braingametests.*' 一条也选不中 / run only the cases whose full name matches; the regex must match the whole name numen.<lowercase class>.<case>, e.g. 'numen\.braingametests\..*', while 'braingametests.*' selects nothing
#   -PgametestMaxTicks=2400              把每条用例的时限收短到这么多刻,看哪些没做成 / cap each case's time limit to find what is stuck
```

## 授权 / License

代码贡献按 LGPL-3.0 授权。插件和兼容模组只要单独发布、通过 API 使用 Numen，可以采用任何协议，包括闭源。
Code contributions are licensed under LGPL-3.0. Plugins and compatibility mods distributed separately that use Numen through its API may use any license, including proprietary.
