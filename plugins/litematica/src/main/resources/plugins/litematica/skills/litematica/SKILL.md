---
name: litematica
description: 主人用 Litematica 画的图纸:怎么列出它们(litematica.schematic.list)、把一张搬进服务器图纸库(litematica.schematic.sync),再用 numen.build.blueprint / numen.build.raise 照着盖。
---

# Litematica 图纸

这个世界装了 Litematica。主人把建筑存成 `.litematic` 文件,放在他自己的电脑上。相关函数在 `litematica.schematic` 组里,写法用 `numen.api.help("litematica.schematic.sync")` 这类调用查看。

## 列出主人的图纸

`litematica.schematic.list()` 交回主人电脑上所有 `.litematic` 的名字(不含扩展名)。这是**主人有什么**,不是服务端有什么。

## 把一张搬进服务器

`litematica.schematic.sync("my_house")` 把其中一张拷进**服务器**的图纸库。搬进去之后,它和别的蓝图文件一样,`numen.build.blueprint("my_house", {x = 100, y = 64, z = -20})` 就读得到。

- 只在**主人本机开着这个世界**时管用(单机,或他自己开、你连上去的服务器)。
- 他连的是**别人开的**服务器时,他的图纸文件不在那台服务器上,搬不了,会如实报错——别反复重试,换用主人能在服务器上放图纸的办法。

## 照着盖

搬进来之后走普通蓝图那一条路:

```lua
local house = numen.build.blueprint("my_house", {x = 100, y = 64, z = -20})
print(numen.build.diff(house))
numen.build.raise(house)
```

图纸自己的原点(文件里的 0 0 0)落在你给的那个坐标;要转就加 `{rotation = 90}`。它和 `numen.build` 认的其它图纸格式是同一套,不必区别对待。
