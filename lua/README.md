# numen-lua

沙箱里的 Lua 5.2,纯 JVM(Java 21):不依赖 Minecraft,也不依赖 Numen 的任何类型,别的模组可以原样嵌进去用。
Numen 用它跑同伴写的组合脚本(`docs/shell.md` §四)。

## 来源

- **上游**:[luaj/luaj](https://github.com/luaj/luaj) master 最新提交
  `daf3da94e3cdba0ac6a289148d7e38bd53d3fe64`(2020-04-01,比 v3.0.2 标签多了 2019 年底的几处修复:弱表、LuaTable 的一处
  罕见错误、模式匹配的报错与空匹配、字符串与数比较的元方法)。上游已停更,这份是我们自己长期维护的起点。
- **许可证**:MIT,原文在 [LICENSE](LICENSE)(随 jar 发出,名为 `LICENSE_numen-lua`);每个源文件保留上游的版权头。
- **只取了解释器的核心**:`src/core/org/luaj/vm2` 下的值类型、表、闭包与解释循环、`compiler`(源码编译到 LuaJ 字节码)、
  `lib` 里的 base、string、table、math 与几个函数基类,外加 `jse` 的 `JseMathLib`(用 `java.lang.Math` 补齐 math 库)。
  **没有拿**:luajc(编译成 Java 字节码)、JME、luajava(反射调 Java)、io、os、debug、package、coroutine、bit32、JSR-223、
  二进制块的装载与导出(`LoadState`、`DumpState`)、`ast`/`parser`(JavaCC 语法树)。
- **包名**:`org.luaj.vm2` 改为 `com.dwinovo.lua.vm`。别的模组(例如 Figura)也带着 LuaJ,同名包在模组加载器的模块系统里会
  冲突;改了包名就是两份互不相干的类。第一次提交(`chore(lua): 引入上游 LuaJ master(daf3da9)的解释器源码原样`)只改了包名,
  之后的改动都在 git 历史里逐条可查。

## 从 FiguraMC/luaj 拿的修复

[FiguraMC/luaj](https://github.com/FiguraMC/luaj)(MIT,与上游同一份许可证;Minecraft 里维护最活跃的分支)在上游 master 之上
有 50 个提交,大半是构建、测试与只为 Figura 的改动,也带着 farmboy0 那一支的修复。逐条看过,拿了这些(都是对着原生 Lua 5.2
的行为修):

| 提交 | 改了什么 |
|---|---|
| `b2bc34c62128` | `LuaTable` 删条目:死槽之后的链要接着删,否则某种布局下表的内部数据不同步 |
| `560a4694e454` | `string.gsub` 的第四个参数为负时当作不限 |
| `6b9dece367bb`(一部分) | `math.max`、`math.min` 只收数 |

没拿的:`8d4bb083e883`(StringLib/CoroutineLib 类改名、`ast.Str` 的越界,我们不带 ast 与协程)、`7a704b421958`(编译目标)、
debug、os、io 库的修复(这些库我们不带)、`b121b65151e4` 与 `66130964c6fc`(fmod 除零、modf 的整数结果:原生 5.2 的结果与
它们的写法有出入,以后对着官方测试集再定)、`99bd46876e46`、`f81bc1e17415`(报错措辞)。

## 我们改了什么

| 文件 | 改动 | 为什么 |
|---|---|---|
| `lib/BaseLib` | 去掉 `load`、`loadstring`、`dofile`、`loadfile`、`collectgarbage`、`print` 与找资源文件的实现 | 沙箱里不装载代码、不碰文件与 JVM;`print` 交给宿主 |
| `lib/StringLib` | string 库表与字符串元表全 JVM 只造一份、锁成只读(`ReadOnlyTable`);去掉 `string.dump`;`string.rep` 先按 long 算总长、记账再分配(上游会乘法溢出、负长度) | 上游的字符串元表是静态共享的,一个沙箱改了 `string.rep` 或 `getmetatable("").__index`,别的沙箱跟着变 |
| `LuaString`、`Buffer`、`lib/StringLib` | 每次为字符串开新字节数组之前向 `Allocation` 记账 | 字符串操作是 Java 代码在干、不计指令;不记账的话 `s = s .. s` 翻倍四十次就要上千 GB |
| `Globals` | 去掉标准输入输出、找文件、包库、调试库的挂点与二进制块的装载;加一个逐条指令的钩子 `Hook` | 只收源码文本(绕过编译器的字节码能做出编译器不会生成的指令);钩子取代调试库,脚本碰不到它 |
| `LuaClosure` | 每条指令前调 `Globals.hook`;报错只带位置、块名照 chunkid 写;错误处理函数只接 `Exception` | 预算、打断、行号都在钩子里;宿主的停止(`Error`)不被吞掉 |
| `LuaError` | 报错写成 `块名:行号: 消息`(上游少一个冒号);`error(消息, 0)` 与非字符串的错误值不带位置(上游照样加) | 和原生 Lua 一样,行号读得出来;脚本自己写好的报错原样交出去 |
| 新增 `Allocation`、`ReadOnlyTable` | 见上 | |
| 新增 `com.dwinovo.lua.LuaSandbox` | 对外的沙箱:预算、虚拟线程、打断、宿主函数、值互转 | 见下 |

## 沙箱与预算

- **全局**:基本函数(去掉上面那些)、`string`、`table`、`math`,加上宿主登记的函数与 `print`。没有协程:LuaJ 的协程每个都起
  一个平台线程,脚本要并发以后再说。
- **字符串只读**:`string` 表与字符串元表是全 JVM 共用的一份只读表,改它报错。要字符串工具就写成局部函数。
- **预算**(`LuaSandbox.Limits`,每段脚本一份):
  - 两次调宿主函数之间的指令数:死循环停在这里,`pcall` 包着也停(停的方式是 `Error`,`pcall` 只接 `Exception`)。
  - 总指令数:跨宿主调用累积的表与数据也有个头。
  - 字符串分配的总字节数:见上。
  - 墙钟:含在宿主函数里等的时间;指令之间每 1024 条查一次,宿主函数返回时查一次。
- **内存**:表的增长由指令数限住(一条指令至多往表里放一项),字符串由字节数限住;没有别的内存上限。

## 运行方式

每段脚本在自己的虚拟线程上跑。宿主函数在这个线程上被调,可以阻塞(等一件慢事做完),不碰调用方的线程;调用方拿到
`Running`,可以随时 `interrupt()`:脚本在下一条指令、或正在阻塞的宿主函数处停下,结局是 `INTERRUPTED`,带停下的那一行。

## 库

宿主可以登记几段库(`Builder.library(块名, 正文)`):每段脚本开跑之前,它们按登记顺序在同一个全局环境里先跑一遍,定义的函数脚本里
直接能调,也可以往宿主函数的表里加函数(`function move.goto_(...) ... end`)。行号只记脚本自己那一段:库里的函数调宿主函数时,
`currentLine()` 说的是脚本里调这个库函数的那一行,结局停在的也是脚本里的那一行;库里出的错,报错原话的开头是库的块名与行号。

## 如何嵌入

```java
LuaSandbox sandbox = LuaSandbox.builder(new LuaSandbox.Limits(1_000_000, 10_000_000, 64 << 20, Duration.ofMinutes(20)))
        .function("work", "dig", args -> {           // 脚本里是 work.dig(...)
            if (!dug(args.get(0))) {
                throw new LuaSandbox.ScriptError("work.dig: out of reach");   // 脚本在调用处得到一个 Lua 错误
            }
            return Map.of("dug", 4L);                // 交回脚本的值:null、布尔、数、字符串、列表、名字到值的表
        })
        .print(line -> log.info(line))
        .build();
LuaSandbox.Running running = sandbox.start("mine", code, List.of("ores"), outcome -> { /* 脚本线程上调一次 */ });
LuaSandbox.Outcome outcome = running.await();       // FINISHED、ERROR(带行号)、SLICE、INSTRUCTIONS、STRINGS……
Object returned = outcome.value();                   // 跑完时 return 的第一个值,换成 Java 值
```

- `LuaSandbox.check(name, code)`:只读不跑,读不通返回带行号的原话。
- `LuaSandbox.currentLine()`:在宿主函数里问脚本是在哪一行调的它。
- `LuaSandbox.KEYWORDS`、`STANDARD_GLOBALS`:登记的表名、函数名不能用它们(`move.goto` 是语法错:`goto` 是保留字),
  登记时就拒;宿主自己定一条改名规则(Numen 加后缀 `_`)。

## 测试

`src/test`:语义(表、闭包、元表、字符串库、错误与 pcall)、沙箱删干净、共享的字符串元表锁住、四种预算、外部打断(忙着与阻塞
在宿主函数里)、宿主函数在虚拟线程上阻塞不碰调用方、值互转、保留字、返回值、库先跑且行号记在脚本那一段。
