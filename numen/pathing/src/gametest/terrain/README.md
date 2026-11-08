# 真实地形固定存档的制作

规划评测(`numen/pathing/src/benchmark`,ADR 0031)的地形是原版世界生成的固定存档:一个目录一块地,每块一个区域文件(32×32 个区块,512×512 格)。
地形不在运行时生成(现场生成的树在区块边界上不确定,生成一次还要几十秒);存档做一次,放进独立的数据集
(<https://github.com/Dwinovo/numen-pathfinding-benchmark>,发布包由 Gradle 下载并核校验码),不进本仓库。

## 存档的样子

```
<名字>/
  region/r.X.Z.mca     区域文件(原版格式,评测直接用原版的 RegionFile 读)
  terrain.properties   种子、维度、区域、维度的高度范围、找站得住的格从哪层往下扫、哨兵方块(制作时自动写出)
```

## 怎么制作 / 重新制作

用 NeoForge 的 GameTest 服务器跑制作套件,种子、区域、维度自己定:

```
./gradlew --no-daemon :numen:neoforge:runGameTestServer -Pgametest=numen_pathing_terrain_bake -PbakeOut=<存档目录> -PgametestSeed=<种子> -PbakeRegion=<X>,<Z> [-PbakeDimension=nether]
```

`-PbakeDimension` 是 `terrain`(主世界的生成器,默认)或 `nether`(下界的生成器与群系),两个维度由数据包定义
(`numen_test:terrain`、`numen_test:terrain_nether`)。制作把区域和外面一圈邻居生成到 FULL(为了让区域边缘的树完整),在高空摆三块金块当哨兵,
存盘,把 `r.X.Z.mca` 和 `terrain.properties` 写进 `-PbakeOut` 指的目录(外面一圈邻居的区域文件不留);同时把整块地的勘测表
(每格地面高度、地面方块、顶面方块)写到运行目录的 `terrain-survey.txt`,挑种子与区域时看地形类型用,不进数据集。

读存档的一端核对哨兵方块:读到的不是这份存档就当场失败。出题与评测怎么用存档、题库格式,见数据集仓库的 README 与 `docs/adr/pathing/0031`。
