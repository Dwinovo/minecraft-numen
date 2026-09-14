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
./gradlew :core:fabric:build :core:neoforge:build
./gradlew :api:common:test :core:common:test
./gradlew :core:neoforge:runGameTestServer
```
