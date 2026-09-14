# 参与贡献 / Contributing

## 原则 / The one rule

合并只看代码质量。用什么工具写都可以，但你必须能解释你提交的每一行为什么这样写。
We merge on code quality alone. Write it with whatever tools you like, but you must be able to explain why every line you submit is written the way it is.

## 开始之前 / Before you start

- 修 bug 可以直接提 PR。/ Bug fixes can go straight to a PR.
- 新功能或牵动多个模块的改动，建议先开 issue 把方案对齐，免得写完了方向不对。/ For a new feature or a change that touches several modules, open an issue first so we agree on the approach before you write it.

## 分支 / Branches

- 每个 Minecraft 版本一条分支，分支名就是版本号，默认分支 `1.21.1` 功能最新。/ Each Minecraft version has its own branch named after the version; the default branch `1.21.1` has the newest features.
- PR 提到你改的那个版本的分支。移植到其它版本由维护者来做。/ Target the branch of the version you changed. Porting to other versions is done by the maintainer.

## 质量标准 / Quality bar

- **一个 PR 只做一件事。** 无法审阅的大 PR 会被要求拆开。/ **One PR, one change.** A PR too large to review will be asked to split.
- **修根因。** 不加兜底、不加吞异常退第二条路的 try/catch、不绕过问题。/ **Fix the root cause.** No fallbacks, no try/catch that swallows and takes a second path, no workarounds.
- **同一个判断只有一个出处。** 动手前先找仓库里有没有已有实现，有就复用。/ **One source of truth per decision.** Look for an existing implementation first and reuse it.
- **不为假想的需求加抽象。** / **No abstractions for requirements nobody asked for.**
- **不顺手重构、改格式、改无关文件。** / **No drive-by refactors, reformatting or unrelated files.**
- **注释写现在怎么做、为什么这么做**，不写"以前是怎样"。/ **Comments say what the code does now and why**, not how it used to be.
- **守住分层**：引擎机制在 `api/`，工具、任务、寻路这些内容在 `core/`。改引擎行为前先读 `docs/architecture-mind-model.md`。/ **Respect the layering**: engine machinery lives in `api/`, content such as tools, tasks and pathing lives in `core/`. Read `docs/architecture-mind-model.md` before changing engine behavior.
- **会改世界或伤到实体的动作只经权限层裁决**，不要在工具或任务里另写"能不能做"的判断。/ **Actions that change the world or hurt an entity are judged only by the permission layer**; don't add your own "may I do this" checks in a tool or task.
- **身体做了什么，模型必须知道。** 不做悄悄传送、悄悄改背包这类模型看不见的副作用。/ **Whatever the body does, the model must be told.** No silent teleports, inventory edits or other side effects the model can't see.

## 提交 / Commits

- 标题一行：`type(scope): 简短描述`，例如 `fix(pathing): 探针无路时回执列出候选路线`。/ One-line subject: `type(scope): short description`.
- 为什么这样做写进代码注释，不写进提交正文。/ Put the reasoning in code comments, not in the commit body.
- 不要改 `gradle.properties` 里的 `version`，发版时维护者来改。/ Don't change `version` in `gradle.properties`; the maintainer bumps it at release.

## 验证 / Verification

提 PR 前在你改的分支上跑通。`1.20.1`、`1.20.2`、`1.20.4` 分支把 `neoforge` 换成 `forge`。
Run these on your branch before opening a PR. On `1.20.1`, `1.20.2` and `1.20.4`, replace `neoforge` with `forge`.

```bash
./gradlew :api:common:test :core:common:test
./gradlew datagenAll
./gradlew :core:fabric:assemble :core:neoforge:assemble
./gradlew :core:neoforge:runGameTestServer
```

改了行为的，PR 里写清楚在哪个版本、哪个加载器里真机试了什么。
If you changed behavior, say in the PR what you tried in game, on which version and loader.
