# 0007 Lua 虚拟机:以 LuaJ 主干为底,自己维护

- 状态:已接受
- 日期:2026-10-01;10-03 虚拟机与沙箱策略分开
- 提交:`b4fcb3457`、`6208e4568`、`cd0a5bbd0`、`f001df9ea`
- 另见:`lua/README.md`

## 背景

Lua(control 0006)先接的是 Cobalt 0.9.9(CC: Tweaked 用的那一份)。接上之后才查到 Cobalt 的 README 明说版本间 API 不保证稳定、建议别处改用 LuaJ、JNLua 或 Rembulan;
装了 CC: Tweaked 时两份同坐标的库还要由加载器挑一份。Java 上的纯 Lua 实现主流是 LuaJ(5.2),上游 2020 年起停更;Minecraft 里各家都是自己维护分支
(CC → Cobalt、Figura → FiguraMC/luaj、OpenComputers 用原生 Lua 加 LuaJ 兜底),没有一份成了公共底子。

## 决定

- 本仓一个纯 JVM 的 `lua` 模块(不碰 Minecraft 与 Numen 的类型):LuaJ 主干最后一次提交 `daf3da9`(2020-04-01)的源码原样引入,MIT;
  只留解析、编译、解释与安全的标准库;包名改成 `com.dwinovo.lua.vm`(别的模组也带着 LuaJ,同名包在加载器的模块系统里会冲突);
  从 FiguraMC/luaj 逐个挑了三处对着原生 5.2 的修复。上游提交号、挑了什么、改了什么都记在 `lua/README.md`。
- 10-03 分界:`vm` 只是一份正确的 Lua 5.2 解释器加几个中立的挂点(每条指令前的钩子、字符串分配的计量、能定死键的表);
  装什么、锁什么、限什么全是 `LuaSandbox` 的策略。
- 沙箱修掉 LuaJ 的几处坑:字符串元表是 JVM 级的静态对象,一个沙箱改了别的跟着变(锁成只读);字符串操作不计指令(按字节记账);
  不给协程(LuaJ 的协程起平台线程,改为每段程序一条虚拟线程,可阻塞等身体);指令与墙钟预算超了抛 `Error`,`pcall` 接不住;外部打断。

## 后果

- 发行包只多约 0.34 MB;别的模组可以原样嵌进去用。
- 上游停更,修复都要自己做;对着官方测试集补一致性留待以后。
