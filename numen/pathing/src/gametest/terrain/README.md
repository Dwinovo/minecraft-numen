# 真实地形固定存档

寻路 GameTest 套件 `numen_pathing_terrain` 在真实地形上跑固定路线。地形不在运行时生成(现场生成的树在区块边界上不确定,
生成一次还要几十秒),而是用仓库里的固定存档:一个目录一块地,每块一个区域文件(32×32 个区块)。

## 目录

```
<名字>/
  region/r.X.Z.mca     区域文件,原样放进真实地形维度 numen_test:terrain 的 region 目录
  terrain.properties   种子、维度、区域、哨兵方块(制作时自动写出)
```

当前只有 `hills`:种子 0,区域 `r.0.0`(方块 x、z 在 0..511),有平地、陡坡、成片树林、断崖和湖。

## 怎么用

每次 GameTest 开跑(两个加载器、所有套件)世界目录都先清空;`-Pgametest=numen_pathing_terrain` 手动选跑时,
`TerrainWorld`(Numen API 的测试夹具)再把根 `build.gradle` 里 `numenTerrain` 指的那块存档放进去。
用例开跑时核对哨兵方块,读到的不是存档(比如路径错了,退回现场生成)就失败。

```
./gradlew --no-daemon :numen:neoforge:runGameTestServer -Pgametest=numen_pathing_terrain
./gradlew --no-daemon :numen:fabric:runGameTestServer -Pgametest=numen_pathing_terrain
```

## 怎么制作 / 重新制作

用 NeoForge 跑制作套件,种子和区域自己定:

```
./gradlew --no-daemon :numen:neoforge:runGameTestServer -Pgametest=numen_pathing_terrain_bake -PbakeTerrain=<名字> -PgametestSeed=<种子> -PbakeRegion=<X>,<Z>
```

它把区域和外面一圈邻居生成到 FULL(为了让区域边缘的树完整),在高空摆三块金块当哨兵,存盘,把 `r.X.Z.mca` 和
`terrain.properties` 写进 `<名字>/`;同时把整块地的勘测表(每格地面高度、地面方块、顶面方块)写到运行目录的
`terrain-survey.txt`,挑路线用,不进仓库。制作出来的存档与路线常量绑在一起(`TerrainRoutes`):重做存档要重新挑路线。
新增一块地:换个 `<名字>`、改 `numenTerrain`、写它的路线。
