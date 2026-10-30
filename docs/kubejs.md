# 用 KubeJS 脚本给她接模组

装了 [KubeJS](https://kubejs.com)(1.21.1 NeoForge)的服务器,可以在服务端脚本里写 Numen 插件:给她加几个函数、听她身上的事、发事件。不用编译、不用发 jar,
改完脚本 `/reload` 就生效,客户端不用更新。脚本写的插件和 Java 插件走同一扇门:在设置的插件页里有它自己的一行(来源"脚本写的"),
开关、出错隔离、体检都和别的插件一样。

> 要 Mixin 才做得到的事,仍得写 Java 插件。脚本函数的质量参差,模型读到的只有你写的说明,所以**说明必填**。

## 一个例子

`kubejs/server_scripts/numen_pack.js`:

```js
NumenEvents.register(event => {
  // 'mypack' 是这个插件的名字空间:她在程序里调 mypack.<组>.<函数>
  event.plugin('mypack', plugin => {
    plugin.group('talk', 'Things she can say.', group => {
      group.fn('greet', fn => fn
        .summary('Say hello to someone by name.')
        .param('who', 'string', 'The name.')
        .option('times', 'integer', 'How many times.', 'say it once')
        .returns('string')
        .example('mypack.talk.greet("Steve", {times = 2})')
        .run(call => {
          const times = call.option('times') == null ? 1 : call.option('times')
          return ('hello ' + call.arg('who') + ' ').repeat(times).trim()
        }))
    })
  })
})
```

保存后在游戏里 `/reload`:她的函数清单里就有了 `mypack.talk.greet`,模型能照着说明调它。再改脚本、再 `/reload`,旧的函数被撤掉,
她调到的是新写的。一个插件声明到一半抛了错,它在插件页里记为失败(原因写在 KubeJS 控制台),别的插件不受影响。

## 能写什么

`event.plugin(名字空间, plugin => { … })` 里,`plugin` 能做的就是 Java 插件那扇门能做的:

| 方法 | 做什么 |
|---|---|
| `plugin.group(组名, 一句话, group => { group.fn(…) })` | 登记一组函数 |
| `plugin.on(事件, body => …)` | 听同伴身上的事:`spawn`、`remove`、`death`、`abort`、`hurt` |
| `plugin.eventType(类型, 恒为急件)` / `plugin.emit(同伴, 类型, 属性, 文字, 急件)` | 登记一种事件,往她的收件箱发一条 |
| `plugin.bodyState(body => 文字)` | 每轮从她身上读一段状态,拼进她看到的身体状态 |
| `plugin.gear({slots(body){…}, kindsOf(body, stack){…}})` | 一个穿戴来源 |

一个函数 `group.fn(名字, fn => …)` 里:`summary`(必填)、`param(名字, 类型, 说明)`、`option(名字, 类型, 说明, 不写时会怎样)`、`rest`、
`returns(类型)`、`example`、`note`、`run(call => …)`。类型名:`string`、`integer`、`number`、`boolean`、`block`、`item`、`id`、`pos`,
后面加 `[]` 是一串(`string[]`)。每个参数的说明必填。`run` 里 `call.arg(名字)` 取必须写的参数,`call.option(名字)` 取可以不写的(没写是 `null`)。

## 动世界只能经受权的动作

脚本不是后门:改世界、伤实体的事走权限层,和她自己的动作一样——放行才做,要问就问主人,不许就带着理由失败。`call` 上有四个:
`useEntity(实体, (her, 实体) => …)`、`attack(实体, (her, 实体) => …)`、`useBlock(格, (her, 格) => …)`、`breakBlock(格, (her, 格) => …)`。
你给的那段只在放行之后跑;函数声明 `fn.pending()` 并把它返回。那一段里做了什么,用 `call.report('…')` 说一句,随结果写进她的回执:

```js
group.fn('pet', fn => fn
  .summary('Right-click an animal within reach.')
  .param('animal', 'string', 'Its id, like cow@3f9a1c from numen.scan.entities.')
  .pending()
  .run(call => call.useEntity(call.arg('animal'), (her, animal) => {
    call.report('petted ' + animal.name.string)
  })))
```

## 关掉与出错

插件页里关掉一个脚本插件,它登记的一切当场撤回,再打开又回来,不用重启。脚本里写错了(语法、运行时的错)看 KubeJS 的服务端日志
(`logs/kubejs/server.log`)。
