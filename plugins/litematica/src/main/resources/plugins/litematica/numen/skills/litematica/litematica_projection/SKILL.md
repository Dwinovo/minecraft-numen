---
name: litematica_projection
description: Litematica 的投影(placement)与分层显示是什么、litematica.placement 的函数交回什么(投影列表、某几层的格、把设计做成投影给主人看)、这些格怎样直接交给 numen.build.place、numen.build.diff。
---

# Litematica 投影

主人装了 Litematica。它是一个只在玩家客户端上运行的蓝图模组:主人把一份蓝图文件(`.litematic`)加载进来、摆在世界的某处,世界里就出现一团半透明的虚影,照着它建。虚影只是主人客户端上的显示,不是世界里的方块。相关函数在 `litematica.placement` 组里,写法用 `numen.api.help("litematica.placement.cells")` 这类调用查看。这些函数都在主人的客户端上执行,读的是主人此刻的 Litematica。

## 投影(placement)

蓝图文件摆在世界某处,就是一个投影:

- **原点**:蓝图自己的原点落在哪一格。
- **子区域**:一份蓝图可以由几块子区域拼成,每块各有位置,也各有开关;关着的子区域不属于这个投影。
- **旋转与镜像**:整个投影可以绕它的原点顺时针转 0、90、180、270 度,也可以镜像;`left_right` 翻转南北方向(z),`front_back` 翻转东西方向(x)。每块子区域还有自己的旋转镜像。方块的朝向跟着一起变。
- **启用**:主人可以把整个投影关掉。
- **选中**:主人同时可以加载好几个投影,其中一个是选中的。

名字相同的投影分不出是哪一个,点名会失败;不点名读的是选中的那份。

## 分层显示(layer range)

主人可以让 Litematica 只显示投影的某几层,免得虚影挡住视线。它是主人的显示设置,不限制谁能读什么:`mode` 是 `all`、`single_layer`、`layer_range`、`all_below`、`all_above`(Litematica 自己的名字),`axis` 是分层所沿的轴(通常是 `y`),`from`、`to` 是显示的最低与最高一层,没有边界的那一头不写。从分层显示能看出主人此刻关心哪一层,但 `cells` 想读哪几层都可以读。

## 函数交回什么

`litematica.placement.list()` 交回 `layer`(上面的分层显示)和 `placements`。每个投影写着:名字、来自哪个文件、原点、占的世界范围(最低与最高两个角和各方向的格数)、旋转、镜像、是否启用、是否选中,以及里面有方块的格数(空气和关着的子区域不算)。

`litematica.placement.cells(64, 66)` 交回一个投影在世界 y 从 64 到 66 那三层里的格(只写一个数就是一层;不点名投影就读选中的,点名写成选项 `{placement = "名字"}`)。每一格是 `{name, pos}`:`pos` 是世界坐标,`name` 是带完整状态的方块,写法和 `/setblock` 一样,例如 `minecraft:oak_stairs[facing=east,half=bottom,shape=straight,waterlogged=false]`。Litematica 自己的摆放已经算进去:原点、子区域、旋转、镜像,方块朝向也跟着转了。空气不交回;投影够不到的层是空表。

一次最多交回 16384 格。要的层装不下就失败(`bad_argument`),提示里是装得下的、从最低一层起的那几层。

## 交给建造

交回的格就是 Cells,原样传给收一串格的函数:

```lua
local cells = litematica.placement.cells(64)
print(numen.build.diff(cells))
numen.build.place(cells)
```

`numen.build.diff` 报还差什么,以及剩下的格要用哪些材料(`materials`)、你身上缺哪些(`short_of`)——Litematica 自己的材料清单是按主人的背包算的,不是你的。

## 把设计给主人看

`litematica.placement.preview(格, 名字)` 把一串格做成主人客户端里的一份投影,摆在格子本来的世界坐标上:

```lua
litematica.placement.preview({{name = "cobblestone", pos = {x = 100, y = 64, z = -20}}}, "my_house")
```

它只改主人的显示,世界不动。格子被存成蓝图目录下 `numen/<名字>.litematic`,主人之后能在 Litematica 里再加载它、改它、分享给别人。同名的再调一次,旧的投影被换掉,所以设计改了再调一次就是更新。名字同时是文件名,只能用字母、数字、空格和 `_ - . ( )`。空气格不显示。交回的是这份新投影,写法同 `list` 里的一项。
