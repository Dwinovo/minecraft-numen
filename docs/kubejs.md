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
| `plugin.on(事件, body => …)` | 听同伴身上与大脑的事:`spawn`、`remove`、`death`、`abort`、`hurt`、`run_started`、`run_ended`、`task_finished`(只读;外接大脑驾驶时没有 `run_*`) |
| `plugin.eventType(类型, 恒为急件)` / `plugin.emit(同伴, 类型, 属性, 文字, 急件)` | 登记一种事件,往她的收件箱发一条 |
| `plugin.bodyState(body => 文字)` | 每轮从她身上读一段状态,拼进她看到的身体状态 |
| `plugin.gear({slots(body){…}, kindsOf(body, stack){…}})` | 一个穿戴来源 |
| `plugin.rangedWeapon(名字, weapon => …)` | 一种远程武器(枪、魔杖),攻击任务像用弓一样用它,见下一节 |
| `plugin.knowledgeSource(种类, source => …)` | 一个知识来源:物品说明或配方(见下) |

一个函数 `group.fn(名字, fn => …)` 里:`summary`(必填)、`param(名字, 类型, 说明)`、`option(名字, 类型, 说明, 不写时会怎样)`、`rest`、
`returns(类型)`、`example`、`note`、`run(call => …)`。类型名:`string`、`integer`、`number`、`boolean`、`block`、`item`、`id`、`pos`,
后面加 `[]` 是一串(`string[]`)。每个参数的说明必填。`run` 里 `call.arg(名字)` 取必须写的参数,`call.option(名字)` 取可以不写的(没写是 `null`)。

## 把整合包的知识交给她

她问"这个怎么做""能拿来做什么""这个是干嘛的"(`numen.inv.recipes`、`numen.inv.uses`、`numen.inv.info`)时,动词会把游戏的配方表、
装了 JEI 的主人客户端和**你的脚本**的答复合在一起,每条注明出自哪个来源。脚本里登记一个来源,写法同函数:

```js
NumenEvents.register(event => {
  event.plugin('mypack', plugin => {
    // 物品说明:整合包作者写给她的话
    plugin.knowledgeSource('info', source => source
      .provides('How the pack wants the item used.')
      .run(item => item == 'mypack:flux_core'
        ? ['Feed it to the Flux Press with a redstone signal; it is not a fuel.']
        : []))

    // 配方:不在原版配方表里的,比如脚本定义的机器
    plugin.knowledgeSource('recipes', source => source
      .provides('Recipes of the pack's Flux Press, which the recipe list does not hold.')
      .notProvided('chance', 'energy')
      .run(item => item == 'minecraft:stick'
        ? [{category: 'mypack:flux_press', item: 'minecraft:stick', makes: 4, ingredients: ['oak_log'],
            catalysts: ['mypack:flux_press']}]
        : []))
  })
})
```

`run` 收物品 id,`info` 返回一串说明文字;`recipes`、`uses` 返回一串配方对象,必填 `category`(配方类型或机器的 id)与 `item`(产出),
可选 `id`、`makes`、`ingredients`、`catalysts`、`categoryName`、`alsoMakes`、`ticks`。`provides`(这个来源多给哪一类信息)必填:来源哪天没答上,
她据此知道缺了什么。`notProvided` 如实写你给不了的字段,不写不猜。

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

## 远程武器:让攻击任务用上模组的枪

模组带来的枪是"远程攻击"这个已有动作上的新名词。两种接法:

- **让攻击任务自己用枪**(`plugin.rangedWeapon`):`numen.fight.attack` 像用弓一样挑枪、走位到枪的环里、瞄准、开枪、连发,没弹药就换别的。
- **让模型一次开一枪**(一个受权的函数):模型调 `tacz.gun.fire(目标)`,见后面的第二个例子。

两种都过权限层:该不该打由权限层按 `attack` 裁决,你的脚本只说"怎么射"。

### 例子一:TaCZ 的枪是一种远程武器

```js
NumenEvents.register(event => {
  event.plugin('tacz', plugin => {
    const IGun = Java.loadClass('com.tacz.guns.api.item.IGun')
    const Operator = Java.loadClass('com.tacz.guns.api.entity.IGunOperator')
    plugin.rangedWeapon('tacz gun', weapon => weapon
      .handles(stack => IGun.getIGunOrNull(stack) != null)
      .readiness((body, stack) => {
        const gun = IGun.getIGunOrNull(stack)
        return gun.getCurrentAmmoCount(stack) > 0 || gun.hasBulletInBarrel(stack) ? 'ready' : 'empty'
      })
      .hitscan(48)            // 命中扫描,射程 48 格
      .standoff(6, 24)        // 她站在离目标 6 到 24 格之间
      .fire((body, stack, target, facing) => {
        const operator = Operator.fromLivingEntity(body)
        const result = String(operator.shoot(facing.pitch(), facing.yaw()))
        if (result == 'SUCCESS') return true
        if (result == 'NOT_DRAW') { operator.draw(() => body.mainHandItem); return 'wait' }
        if (result == 'NEED_BOLT') { operator.bolt(); return 'wait' }
        return ['COOL_DOWN', 'IS_DRAWING', 'IS_RELOADING', 'IS_BOLTING', 'IS_MELEE'].includes(result) ? 'wait' : false
      }))
  })
})
```

`weapon` 上能写的:

| 方法 | 做什么 |
|---|---|
| `handles(stack => 布尔)` | 背包里这件是不是这种武器(必填) |
| `readiness((body, stack) => 'ready' \| 'loadable' \| 'empty')` | 此刻能不能射:现成的、要先 `prepare`(换弹、拉栓)、没弹药(必填) |
| `hitscan(射程)` 或 `arc(初速, 重力, 阻力, 射程)` | 弹道:一条线,或受重力的弹射物(必填其一) |
| `standoff(内沿, 外沿)` | 站位的环;不写,内沿 3 格、外沿是射程的四分之三 |
| `prepare((body, stack) => …)` | `readiness` 说 `loadable` 时每刻调一次,直到变成 `ready` |
| `fire((body, stack, target, facing) => …)` | 射一发(必填)。身体已经对准了目标。返回 `true` 是射出去了,返回 `'wait'` 是这一刻射不了(冷却、还没切好枪)下一刻再来,别的算哑火;连着两发哑火攻击任务就收场、回执说这件武器没射出去 |

几件会踩的事:

- **类过滤**:`Java.loadClass` 受 KubeJS 的类过滤约束。默认放行,各模组 jar 根上的 `kubejs.classfilter.txt` 里 `- 前缀` 是拒绝、`+ 完整类名` 是放行。`Java.loadClass` 报类不允许,就是你的整合包里有一个模组的过滤拒绝了它。
- **`Supplier<Float>`**:TaCZ 这类接口要的 `Supplier<Float>`,脚本里写箭头函数交回的是 JavaScript 的数,到 Java 一侧不是 `Float`,会 `ClassCastException`。用 `facing.pitch()`、`facing.yaw()`(现成的 Java 供应器),或者函数版里的 `call.facingTo(目标)`。
- **TaCZ 的计时按真实时间算**:切枪、射击冷却用的是毫秒的真实时间,不是游戏刻。真实服务器每刻 50 毫秒,感觉不到;在不限速的无头测试服务器上(GameTest、跑得比真实时间快的测试)刻会飞快地过,切枪还没完成"准备上限"(200 刻)就用完了,会被当成哑火。在无头环境里测枪,先切好枪、等真实的一两秒再让她打。这是 TaCZ 的性质,Numen 的上限不会为它放宽。

### 例子二:模型一次开一枪

```js
NumenEvents.register(event => event.plugin('tacz', plugin => {
  const Operator = Java.loadClass('com.tacz.guns.api.entity.IGunOperator')
  plugin.group('gun', 'Guns from Timeless and Classic Guns.', group => {
    group.fn('fire', fn => fn
      .summary('Fire the gun in your hand once at a creature.')
      .param('target', 'string', 'Its id, like zombie@3f9a1c from numen.scan.entities.')
      .returns('string')
      .pending()
      .example('tacz.gun.fire("zombie@3f9a1c")')
      .run(call => call.attack(call.arg('target'), (her, target) => {
        const facing = call.facingTo(target)
        const result = String(Operator.fromLivingEntity(her).shoot(facing.pitch(), facing.yaw()))
        call.report('fired the gun: ' + result)
        return result
      })))
  })
}))
```

这两个例子在真的挂着 TaCZ 的服务器上验过(见 `docs/adr/control/0017-ranged-weapon-providers.md` 的实现说明)。TaCZ 不随 Numen 发。

## 关掉与出错

插件页里关掉一个脚本插件,它登记的一切当场撤回,再打开又回来,不用重启。脚本里写错了(语法、运行时的错)看 KubeJS 的服务端日志
(`logs/kubejs/server.log`)。
