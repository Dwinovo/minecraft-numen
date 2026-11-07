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
./gradlew :numen:pathing:pathingBench -PpathingDataset=<目录>   # 寻路规划评测(纯 JVM,不要服务器;数据集在 Dwinovo/numen-pathfinding-benchmark,改寻路前后各跑一次,pathingBenchDiff 对比) / pathfinding planning benchmark (plain JVM, no server; dataset in Dwinovo/numen-pathfinding-benchmark; run before and after a pathing change, compare with pathingBenchDiff)
./gradlew :numen:neoforge:runGameTestServer
./gradlew :numen:fabric:runGameTestServer
./gradlew :numen:neoforge:runGameTestServer -Pgametest=numen_slow,numen_pathing_slow   # 慢批:时限超过 600 刻的用例,含寻路可靠性矩阵(两分多钟);只在改了寻路的执行或走法前提时跑 / the slow batch: cases whose time limit is over 600 ticks, including the pathing reliability matrix (2+ minutes); run it only when you change pathing execution or move preconditions
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
# 排查与证明稳定的开关,加在 runGameTestServer 命令后面 / switches for investigating and proving stability, appended to a runGameTestServer command:
#   -PgametestOnly='<完整用例名的正则>'   只跑名字匹配的用例(NeoForge、Fabric、Paper);正则要匹配完整名 numen.<类名小写>.<用例名>,如 'numen.braingametests.*'(用 . 不要用 \.,参数文件会把反斜杠原样带进去),写 'braingametests.*' 一条也选不中 / run only the cases whose full name matches (NeoForge, Fabric, Paper); the regex must match the whole name numen.<lowercase class>.<case>, e.g. 'numen.braingametests.*' (write . rather than \. , the argument file passes backslashes through verbatim), while 'braingametests.*' selects nothing
#   -PgametestCopies=16                  把选中的每条用例在同一次开服里铺 16 份并行跑(NeoForge、Fabric),证明稳定用这个,不要重启服务器连跑;第 2 份起用例名加 #份号 / run every selected case in 16 parallel copies within one server start (NeoForge, Fabric); this is how to prove a case stable, rather than restarting the server over and over; copies from the 2nd carry #<number> in the name
#   -PgametestOrigin=x,y,z               场地起点;不给就是固定的默认一处,每次开服相同。失败的用例在日志里写着起点与种子,照着给就能原样重放(NeoForge、Fabric、Paper) / the origin of the test plots; by default one fixed place, the same on every start. A failed case logs its origin and seed; pass them back to replay it as it was (NeoForge, Fabric, Paper)
#   -PgametestMaxTicks=2400              把每条用例的时限收短到这么多刻,看哪些没做成(Paper) / cap each case's time limit to find what is stuck (Paper)
# 每条用例收场时日志里有一行 [numen-gametest-ticks] passed|failed 用了刻数/时限 墙钟毫秒 用例名,定时限看它 / each finished case logs one [numen-gametest-ticks] passed|failed used/limit wall-ms name line; set time limits from it
```

### 验证的节奏 / Verification cadence

- 改代码时只跑相关的那一批:`-Pgametest=<套件>` 选套件(`numen`、`numen_pathing`),`-PgametestOnly` 再筛到相关的用例,加上相关的单测;不 clean,不 datagen(改了语言文件除外)。
  While you work, run only the relevant batch: `-Pgametest=<suite>` picks the suite (`numen`, `numen_pathing`), `-PgametestOnly` narrows it to the cases you touch, plus the unit tests that cover them; no clean, no datagen (unless you changed language files).
- 交付前跑一次默认整套,NeoForge 与 Fabric 两个平台并行;失败就查根因,不重跑碰运气,用例里也不写重试。
  Before handing in, run the default suite once, NeoForge and Fabric in parallel; when something fails, find the cause rather than rerunning and hoping, and never add retries to a case.
- 慢批(`numen_slow`、`numen_pathing_slow`)和 `pathingBench` 只在改了寻路的执行或规划时跑。默认整套只收时限不超过 600 刻的用例,超过的自动归慢批。
  Run the slow batch (`numen_slow`, `numen_pathing_slow`) and `pathingBench` only when you changed pathing execution or planning. The default suite holds only cases with a time limit of at most 600 ticks; anything above goes to the slow batch automatically.
- 怀疑一条用例不稳,就用 `-PgametestCopies` 在一次开服里铺多份并行,看全部结果;不重启服务器连跑。
  When a case looks unstable, spread copies of it with `-PgametestCopies` within one server start and read all the results; do not restart the server in a loop.

## 授权 / License

代码贡献按 LGPL-3.0 授权。插件和兼容模组只要单独发布、通过 API 使用 Numen，可以采用任何协议，包括闭源。
Code contributions are licensed under LGPL-3.0. Plugins and compatibility mods distributed separately that use Numen through its API may use any license, including proprietary.
