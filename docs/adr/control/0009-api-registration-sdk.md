# 0009 Lua API 登记:一个带 `@Fn` 的函数就是契约

- 状态:已接受
- 日期:2026-10-04
- 提交:`e4197eefa`
- 另见:`docs/shell.md` §十三

## 背景

Numen 是"注册 Lua 框架的第一个模组",插件与 Numen 走同一扇门。旧的登记由命令行时代的几层拼起来(`CommandGroup`、`Param`、`ArgType`、`ScriptType`),
一个函数的参数、返回、帮助散在几处。用户的要求:照主流、对开发者舒服、对模型友好、组合解耦、不留技术债。

## 决定

照主流的写法(CC: Tweaked `@LuaFunction`、OpenComputers `@Callback`、Python 类型注解生成工具结构、Cloudflare Code Mode 的 TS 接口、Spring 的注解方法):
- 一个函数是一个带 `@Fn` 的静态方法,参数是一个 record(`@Doc` 说明、可省的进选项表),返回是一个 record;
- `ServerCall`/`ClientCall` 定在哪一端执行,返回类型定种类(当场返回、等身体干完);失败只抛 `ApiError`,`hint` 写成能照抄的下一步;
- 组件各管一件:`ApiRegistry`、`Binder`、`LuaCodecs`、`Dispatcher`、`ApiDocs`、`ApiTester`;
- 登记只拦会破坏系统的硬错误,不强制写法规范——SDK 让好写法成为默认,评测的分数说话。

## 后果

- 约 83 处登记全迁,旧框架删净;评测按函数统计调用与失败。
- 帮助、索引、类型签名都从登记处生成,不会和实现漂移。
- 大量 Java 21 写法,往 Java 17 的 1.20.x 分支移植要改写。
