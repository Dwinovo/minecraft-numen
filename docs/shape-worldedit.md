# numen.shape 对齐 WorldEdit

状态:第一期(纯几何)已实现(10-27),第二、三期待做;下面写的是现状,和 WorldEdit 源码对不上的地方已按源码改过。WorldEdit 源码:`D:\01_Projects\WorldEdit`(提交 8aa2205),下文 `ES` 指 `worldedit-core/.../EditSession.java`。

## 一、定位

- `numen.shape` 是她的"手":**叫法和操作对齐 WorldEdit**,但**只产出或变换一串格(Cells)**,从不改世界。落进世界只有一条路:`numen.build.place`(征询、报料、手放、权限层、如实回执)。
- 两个名词照 WorldEdit 分:
  - **选区**(`Region`):带形状的选区(cuboid、cyl、ellipsoid、sphere、poly、convex),记得自己的形状参数,所以 `expand`、`contract` 这些能照 WE 按形状改;`r:cells()` 展开成只有位置的格。
  - **剪贴板**:每格带方块的 `Cells`,和现在一样。
  - 收"一片格"的操作两样都收:只有位置的 `Cells`(diff、扫描交回的)就当一块任意形状的选区。
- **凡是 WE 的操作都对齐**。改世界的那些拆成"读世界 → 算出格",落进世界仍只走 `numen.build.place`。只有本质上不是"放方块"的才不做(见第二节末)。
- 不做审查、不替模型判断好坏;要什么形状由模型组合。
- WorldEdit 是 GPL-3.0,Numen 是 LGPL-3.0:**只对齐语义和名字,不搬代码**。

## 二、清单

### 生成(返回 Cells;pattern 省略时返回选区,空心的与锥、金字塔、线、曲线是任意形状的选区)

| Numen | WorldEdit | 语义要点(以 WE 源码为准) |
|---|---|---|
| `cyl(at, pattern, radii, height)` / `hcyl` | `//cyl` `//hcyl` | 底面中心在 at;radii 一个或两个(x、z),可小数;判据 `(x·1/R)²+(z·1/R)² ≤ 1`,R = r+0.5(ES:1762),乘倒数的写法照 WE,边界上的格才一致;height 负数向下、0 没有格;半径下限 1 |
| `sphere(at, pattern, radii, raised)` / `hsphere` | `//sphere` `//hsphere` | radii 一个或三个(x、y、z);R = r+0.5;`raised` 即 `-r`,中心上移 (int)ry;半径下限 0 |
| `cone(at, pattern, radii, height)` / `hcone(…, thickness)` | `//cone`(`-h` 与厚度) | 半径**不** +0.5(ES:1845);厚度只有空心版有,缺省 1 |
| `pyramid(at, pattern, size)` / `hpyramid` | `//pyramid` `//hpyramid` | 共 size 层,底边 2·size−1;空心版只留外圈、无底 |
| `line(points, pattern, thickness)` / `hline` | `//line`(含 `-h`) | points 是两个或更多的 Pos(折线);thickness 是球半径,不 +0.5,不小于 0(ES:2903) |
| `curve(points, pattern, thickness)` / `hcurve` | `//curve` | Kochanek-Bartels 样条,张力、偏置、连续性全 0(两端钳住的 Catmull-Rom),每格长度采 10 次;长度的数值算法照 WE,采到的格才一致 |
| `generate(region, pattern, fn, mode)` / `hgenerate` | `//generate`(`-h`) | WE 的表达式换成 Lua 函数 `fn(x,y,z) → boolean`;`mode`:`"unit"`(缺省,−1..1)、`"world"`(`-r`)、`"center"`(`-c`)、或一个 Pos(`-o`,以那一格为原点——放置点由调用者给出);空心版连邻格也问一遍 fn |
| `cuboid(a, b)` | 选区 cuboid | 只出选区,用 `:set` 等上方块 |
| `poly(points, minY, maxY)`、`ellipsoid(c, radii)`、`convex(points)` | 选区 poly2d / ellipsoid / convex | 判据见 `regions/Polygonal2DRegion.java:344`、`EllipsoidRegion.java:185`;poly 至少三点,convex 要四个不共面的点(WE 对共面的点用的判据是"在包围盒里的那个平面",不是凸包,这里直接报错) |

### 选区上的操作(纯几何)

| Numen | WorldEdit | 要点 |
|---|---|---|
| `r:set(pattern)` | `//set` | 每格放 pattern |
| `r:walls(pattern)` | `//walls` | 长方体:四侧不含顶底;别的形状:有邻格在选区外的格,高度范围之外算"在里面",所以顶和底开着(ES:1386) |
| `r:faces(pattern)`(`r:outline`) | `//faces` | 长方体六面;别的形状:6 邻接有一格在选区外的格。球与圆柱按这条判据就是 `hsphere` 与带顶底的 `hcyl`,所以 `hsphere`、`hcyl` 就是它们的 `faces`、`walls`,只有一份实现 |
| `r:center(pattern)` | `//center` | 偶数长的轴放 2 格,共 1/2/4/8 格 |
| `c:stack(count, dir, opts)` | `//stack` | count 不含原件,交回的是原件加副本;位移 = k·dir·外包盒尺寸,副本不许压着原件;`opts.skip_air`(`-a`);`opts.cells`(`-r` 按格计) |
| `c:move(count, dir, leave)` | `//move` | 交回的是"要写什么":目标处的格,加原处减目标的格填 leave(缺省 air);原点跟着动 |
| `c:paste(at)` | `//paste` | 剪贴板原点对到 at |
| `c:rotate(y, x, z)` | `//rotate` | 度数,正数 = 从上看顺时针;绕原点;先 z、再 x、再 y(WE 的 `rotateY(-y)·rotateX(-x)·rotateZ(-z)`);每格落在 `floor(R·d)`,非 90° 倍数会留缝,同 WE |
| `c:flip(dir)` | `//flip` | 绕原点那一格镜像;dir 是名字或向量 |
| `c:revolve(count)` | `//revolve` | 绕竖直轴均匀转 count 份(`opts.reverse` 逆时针);落点 `floor(R·d + 0.5)`,空气格不复制 |

### 读世界的操作(读,不写;读的结果仍是 Cells)

| Numen | WorldEdit | 读什么 |
|---|---|---|
| `copy(region, mask)` | `//copy` | 选区里每格此刻的方块(空气也在,不带方块实体数据);原点 = 她站的格。没过蒙版的格读成空气 |
| `cut(region, leave, mask)` | `//cut` | 返回一张表 `{clipboard, cleared}`:`clipboard` 就是 copy 的结果,`cleared` 是原处填 leave(缺省空气)的格。一次调用一份结果、字段有名字,不用位置返回值 |
| `r:replace(mask, pattern)` | `//replace` | 选区每格当前方块;mask 缺省 `#existing` |
| `r:overlay(pattern)` | `//overlay` | 每列顶上第一个非空气格;可能到 maxY+1;顶上已有方块的列跳过 |
| `r:hollow(thickness, pattern)` | `//hollow` | 每格挡不挡移动;从边界泛洪找外部,曼哈顿距离 ≥ thickness 的换成 pattern(缺省 air)。边界全是实心时会整片挖空,这是 WE 的行为,照搬 |
| `r:naturalize()` | `//naturalize` | 每列的草、泥土、石头:顶上草,下面三层泥土,再下石头 |
| `r:smooth(iterations, mask)` | `//smooth` | 高度图,高斯(5, 1.0),浮点核、`floor`,列升降照 WE |
| `r:trim(mask)` | `//trim` | 过蒙版的格的外包盒,交回长方体选区;一格都不过就报错 |
| `r:deform(fn)` | `//deform` | WE 的表达式换成 Lua 函数:`fn(x, y, z)` 返回源格的 x, y, z;源格一次读完。不做 `-l`(剪贴板作源) |

读世界只读已加载的区块;选区里有没加载的格就整个调用失败,并说出是哪一片(附 `numen.move.to` 的提示)。

世界里一格是什么、挡不挡移动、一列多高,各只有一个读法:Java 的 `LoadedBlocks`(只经 `BlockScanner.loadedChunk` 读已加载区块)。WE 的材质判据对到原版:`#solid` 与"挡移动"是 `blocksMotion()`,`#fullcube` 是整格碰撞形状,空气是 `isAir()`,液体是 `LiquidBlock`;不手写方块名单。

读世界的 Java 原语在 `numen.shape` 组里,名字与 Lua 里 WE 叫法的包装不同,免得合并进同一张 `numen.shape` 表时冲突:`read`、`matching`、`interior`(hollow)、`tops`(overlay)、`naturalized`、`smoothed`、`restated`(`^`)、`holes`(fill、fillr);`drain` `fixwater` `fixlava` `snow` `thaw` `green` `gravity` `heightmap` 没有对应的 Lua 包装,直接叫 WE 的名字。它们都返回 Cells。结果里与世界已经一样的格不写(省格数)。

### 模式(pattern)与蒙版(mask)

- **pattern**:照 WE 写法
  - `stone`、`oak_stairs[facing=north]`;
  - `50%stone,50%cobblestone`(相对权重,不写 % 的权重是 1);
  - `##logs`、`##*logs`、`*oak_log`;
  - 还可以直接给一串 Cells,当 `#clipboard` 平铺。
- **mask**:照 WE 写法
  - `stone,dirt`、`!air`、`#existing`、`#solid`、`#surface`;
  - `>m`、`<m`、`~m`、`%n`;
  - 空格表示"且"。
  - 蒙版测的是世界里的方块,所以只出现在读世界的操作上。解析与求值在 Java 一处(`ShapeMasks`),`stone,dirt` 里没写的属性匹配任意值;还有 `#air`、`#fullcube`、`#exposed`、`##tag`、`^[状态]`。`=表达式` 蒙版不做(报错并说明)。
  - `^` 改状态的 pattern 单独成一条文本(如 `^[waterlogged=true]`),不能放进加权列表;`^type` 部分必须是一种方块。
- 随机照 WE:每格单独掷、不带种子。`Cells` 是一个值,算一次存进变量,`diff` 和 `place` 收的是同一份,所以不需要另加种子。

### 选区编辑(照 WE 按形状)

`r:expand` / `r:contract`、`r:expand_vert()`(`//expand vert`,读 `numen.shape.y_range()`)、`r:shift`、`r:outset` / `r:inset`(`opts.horizontal` 即 `-h`、`opts.vertical` 即 `-v`),各形状的语义照 `SelectionCommands.java:392/429/458/478`、`ExpandCommands.java:124`:长方体按边,圆柱与球按半径(改变量加起来要是偶数、绕中心),多边形只上下,凸体与任意形状不能改。选区本身不变,每次返回改过的新选区。方向写 `"north"`…`"down"`(或 `n s e w u d`)、向量、或一串方向。`r:trim(mask)` 读世界,在上面读世界的表里。

### 调原版生成器的(读世界 → 格)

| Numen | WorldEdit | 说明 |
|---|---|---|
| `feature(at, id)` | `//feature` | 一个配置地物(`minecraft:oak`、矿脉、湖、花丛……)种在 at |
| `structure(at, id)` | `//structure` | 一个结构(`minecraft:swamp_hut`、村庄……)在 at 所在区块生成;高度由区块生成器定,同 WE |
| `r:forest(tree, density)` | `//forest` | 选区每列按密度(百分数,缺省 5)在最高的非空气方块上头种一棵树,每列最多试 10 次 |
| `forestgen(at, size, tree, density)` | `forestgen` | `cuboid(at ± size)` 的 forest,size 缺省 10 |
| `r:flora(density)` | `//flora` | 草地长草与小花(300:5:5),沙子长枯灌木与仙人掌(30:20:300 的空气),密度缺省 5 |
| `pumpkins(at, radius)` | `pumpkins` | 草地上零星的南瓜田,密度 2%,半径缺省 10,高度 -5 到 +10 |

- **id 走注册表,不写名单**:地物是 `CONFIGURED_FEATURE`、结构是 `STRUCTURE`、树是"底下的地物是树(或珊瑚树)的 `PLACED_FEATURE`"(`minecraft:oak_checked` 一类),模组注册的都自动可用。树另有 WE 自己的 `worldedit:pine`(WE 那棵简陋的松树)与 `worldedit:random`(从树里随机挑)。
- **`forest` 的缺省树是 `minecraft:oak_checked`**:WE 的缺省值写的是 `tree`,而它的树注册表里没有这个名字,是它自己的遗漏;这里取它本意,橡树。
- **记录用的世界**(`RecordedLevel`,只此一份):原版生成器要一个 `WorldGenLevel`,这里给它一个代理,覆盖层叠在真实世界上。
  - 读:方块照实读真实世界,经 `LoadedBlocks`(全仓读世界的唯一一处),只读已加载的区块,读到没加载的整个调用失败并说出是哪一片;同一次生成里先写的后面读得到(读的是覆盖层叠上去的样子)。高度图、群系、随机数也在代理里答,不用真实世界的缓存与随机数发生器。
  - 写:只记进覆盖层,往没加载的区块写同样算读失败。真实世界从头到尾不动:不触发方块更新、不生成实体、不改光照和高度图缓存、磁盘上不留东西;所有返回 void 的方法(计划刻、方块更新、音效、游戏事件)一律吞掉。
  - 一次尝试失败(树长不出来)退回到尝试之前的覆盖层。
  - 生成器加的实体、带数据的方块实体(箱子的战利品、刷怪笼的怪、告示牌的字)不进 Cells(Cells 只有方块状态,`place` 也不收 NBT),返回值里 `lost` 如实列出是什么、在哪;方块本身在 `cells` 里。
  - 返回 `{cells, lost}`(`feature`、`structure`、`forest`)。`flora`、`pumpkins` 是 WE 自己的算法,没有生成器可跑,只返回 Cells。
  - 液体照实留在 Cells 里(水、岩浆、含水状态):`place` 不放液体、也排不掉(液体会流动,放下后的样子和清单对不上):液体格不放、其余照放,回执 `liquid` 一类说有几格、下一步(桶倒、填方块或舀起水源),`diff` 同样单列。这里不替模型删格。
  - 地物没长出来(它自己的放置规则说不行)、结构没有合法的布局:调用失败并说明,不返回空。
  - 局限:生成器若经 `level.getLevel()` 拿到真实的 `ServerLevel` 再去读写,走的就不是记录世界(原版的地物与结构不这样做);强转 `ServerLevel` 的会失败,调用报"跑不起来"。
- **`regen` 不做**:它要起一个临时的 `ServerLevel`(WE 是新建一个带临时存档目录的世界,同步生成区块、等主线程排空,再删目录)。代价是主线程阻塞好几秒、内存里多一个世界与区块生成线程、磁盘上建删临时目录、各平台会对新世界发"世界加载"事件(别的模组会响应),构造器还随版本变。和"不在磁盘留任何东西、不改真实世界"冲突,没有干净做法。可选的方案和代价见汇报。

### 以世界为准的维护命令(读世界 → 格)

`drain` `fixwater` `fixlava` `snow` `thaw` `green` `removeabove` `removebelow` `removenear` `replacenear` `fill` `fillr` `extinguish` `snowsmooth`,以及笔刷才有的 `gravity` `splatter` `heightmap` `morph` `erode` `dilate` `raise` `lower`:都按 WE 的判据读世界、算出要放(或挖成空气)的格。与 WE 的差别:
- `removeabove` `removebelow` `splatter` 不读世界:前两个是一个长方体设成空气(缺省高度读 `y_range`),`splatter` 是球上随机。
- `removenear` 的缺省半径 50 是个装不进一份 Cells 的立方体,要给够用的半径。
- `heightmap` 收一张 0 到 1 的 Lua 网格(每行一串数,第一行是南边),最近邻取样,不读图片文件。
- `fill`、`fillr` 的泛洪、半径判据、深度照 WE(`fill` 四个水平方向加向下,`fillr` 六个方向)。

- `extinguish(at, radius)` 是 `removenear(at, "fire", radius)`,半径缺省 40(命令的那个正方体);笔刷的球形版就是 `sphere(at, nil, r):replace("fire", "air")`,不另开。
- `snowsmooth`(`r:snowsmooth(iterations, layers, mask)`)和 `smooth` 共用同一份高斯核与卷积(`TerrainReads`),只是高度带小数、一层雪是 1/8 格。
- `morph(at, radius, opts)`:先侵蚀后膨胀,每一遍读上一遍的结果;`erode`、`dilate` 是它的两个预设(`(2,1,5,1)` 与 `(5,1,2,1)`)。
- `raise(at, radius, shape)`、`lower(...)`:WE 里是"每格取下面(上面)一格"的 deform 笔刷,就是 `Region:deform`;形状是 `"sphere"`(缺省)、`"cuboid"`、`"cyl"`。

### 选区信息(读世界或几何,不出格)

| Numen | WorldEdit | 返回 |
|---|---|---|
| `r:size()` | `//size` | `{kind, min, max, size, distance, blocks}`:外包盒、两角距离、格数;不读世界 |
| `r:count(mask)` | `//count` | 过蒙版的格数 |
| `r:distr(opts)` | `//distr` | `{name, count}` 的列表,多的在前;`opts.mask` 只数过蒙版的,`opts.separate` 按方块状态分 |

扫描工具里没有同样的统计(`numen.scan` 数的是成团的方块),所以是新的一处;读世界走 `LoadedBlocks`,蒙版走 `ShapeMasks`。`-c`(剪贴板)与分页不做:Cells 是值,没有会话剪贴板;列表是完整的。

### 不做的,及原因

只有本质上不是"放方块"的:
- **生物群系命令**(`//setbiome` 等):改的是群系不是方块,`place` 放不了。
- **历史**(`//undo` `//redo`):她不做隐形撤销,每次放下都如实回执。要恢复,就在动手前 `copy` 一份,之后再放回去。
- **schematic 文件**(`//schem`):蓝图文件归 Litematica 插件。
- **选区魔杖、笔刷绑定**:都是"把操作绑到物品上、对准哪就在哪做"的玩家界面。她直接在某个位置调操作。笔刷里"出方块"的操作单独做成了"在某处、某半径内"的函数(上面的 morph、erode、dilate、raise、lower、extinguish、snowsmooth、gravity、splatter、heightmap、smooth、feature、forest);笔刷里只是换个形状(sphere、cylinder、set……)的不另做,就是选区加 `:set`。

### Numen 自己有、WE 没有的,保留

`union`、`minus`、字符网格 `layer`、`Pos` 运算、`cells(list)`。这些是组合用的原语,WE 没有对应物,叫法不变。

## 三、要改掉的现状

1. **空心圆柱和空心球的定义改成 WE 的**:向外一步(+x、+z,球还有 +y)出界才留下,是最薄的连续壳(ES:1801、1968)。现在用的距离带,r=4 的空心球多出 24 格。`BuildGameTests` 里空心塔筒那条用例的判据随定义一起改。
2. **方块状态的旋转和翻转只有一份实现,在 Java**(`BlockTurns`),删掉了 `shape.lua` 里手写的 `turned()`。
   - 原版每种方块自己实现了 `BlockState.rotate(Rotation)` 和 `mirror(Mirror)`:水平面里 90° 倍数的转动与水平镜像一律走它们(铁轨的 `shape`、合成器的 `orientation`、门的 `hinge`、楼梯的内外角、模组方块都自己处理)。
   - 原版不覆盖的(竖直翻转、绕 X/Z 转、不是 90° 倍数的转动)照 WE 的 `BlockTransformExtent`:方向属性取变换后最接近的方向,`axis`、16 档 `rotation`、东南西北四面、铁轨 `shape`、合成器 `orientation` 同理;`half`/`type` 随竖直翻转上下互换,楼梯 `shape` 与箱子 `type` 随水平翻转(含绕 X、Z 转半圈)左右互换。变换后落不进这个方块取值范围的(只有 x、z 两种的 `axis` 转到竖直)保持原样。
   - Lua 把变换写成 3×3 阵(旋转与翻转的约定只在 Lua 里一处),Java 按阵分路:`numen.shape.transform_blocks(blocks, matrix)`。没变的写法原样交回,变了的保留方块名、写了的属性及先后、NBT 原文,转后变了值的没写属性补在后面;NBT 不丢。
3. **转心照 WE 用剪贴板原点。** Cells 带一个 `origin`:
   - 生成函数记放置点 `at`(选区也记着,`:set`、`:cells()` 带给 Cells),`copy` 记她当时站的格(第二期);
   - `shift`、`paste`、`move` 跟着移动;没给原点的 Cells(扫描、`numen.shape.cells(list)` 的)原点是外包盒最小的那一角;`numen.shape.cells(list, origin)` 可以指定。
   - 原点不在数据里:Lua 的表只要多一个字段,交给 Java 时就不是一串格了,所以它挂在这串格自己的元表上(`__origin`),`numen.build.place` 看到的仍是纯粹的一串格。
   原来缺省绕世界原点 (0,0,0) 转,结果离工地几十万格,已改掉。
4. **改名一次改完,不留别名**:
   - `box` 改为 `cuboid(a, b):set(...)` 或 `:faces(...)`;
   - `cylinder` 改为 `cyl` / `hcyl`;`line(from, to, block)` 改为 `line(points, pattern, thickness)`;
   - `hollow` 参数改为 h 前缀的函数;
   - `rotate(quarters, origin)` 改为 `rotate(度数)`,原点在 Cells 上。
   bench 解法、测试、文档里的旧名字同一批改掉(技能与提示词里本来没写)。

## 四、实现分工

- **几何留在 Lua 模块 `numen.shape`**:模型能读它的源码,方法也挂在 `Cells` 上,和现在一样。
- **Lua 做不了的,由 Java 提供少量原语,Lua 调用**(`numen.shape` 组里,`tools/shape/`):
  - `transform_blocks(blocks, matrix)`:方块状态的旋转和翻转(原版 `rotate`、`mirror`,不覆盖的照 WE);
  - `pattern(text)`:pattern 解析(标签、随机状态、`/setblock` 写法校验,原版 `BlockStateParser` 经 `BuildStates.parse`),交回带权重的几组方块,每格怎么掷骰子在 Lua 里;
  - `max_cells()`、`y_range()`:一串格的上限、维度的最低最高一格;
  - 读世界(见第二节):`LoadedBlocks` 一处读,`ShapeMasks` 一处解析蒙版,`TerrainReads` 放各算法;整块在一次宿主调用里读完(一次运行最多 200 次宿主调用)。
  每件只有一个实现,Lua 不再自己猜方块状态。
- 上限仍是 `LuaCodecs.MAX_CELLS` = 16384,超了报错并说明怎么拆(Lua 向 `max_cells()` 问一次,不再抄一份数字)。一次运行两次宿主调用之间的指令预算是 100 万条:几千格的操作没事,一万五千格上下的转、翻、空心大球会撞上它,拆成几块各放一次即可。
- 图案里 Cells 平铺(`#clipboard` 的位置):外包盒在世界里重复,格在盒里同一位置的方块;盒里没有格的地方这一格没有方块(那一格不在结果里)。
- 选区的包围盒是它的格真正的外包盒(WE 的圆柱、椭球的包围盒在半径带 .5 时少一格,不照搬)。选区不按世界高度夹(`expand_vert` 除外),放不进世界的格由 `numen.build.place` 报。

## 五、验证

- **单测**:每个生成函数在若干半径上的格集合,对照 WE 的判据重算,包括空心壳的格数。pattern 的权重分布。旋转和翻转对楼梯、铁轨、门、合成器的状态,以原版 `rotate`、`mirror` 的结果为准。
- **GameTest**:读世界的四件(copy、replace、overlay、hollow)各一条,在代码搭的场景里断言交回的格;再加一条"copy → rotate → paste → place"建出来和原物转过的样子逐格一致。
- **不跑 bench**:场景由用户统一安排。

## 六、分期

1. **第一期(纯几何)**:Region 及选区编辑、生成函数、选区操作、pattern、旋转和翻转改走原版、改名。
2. **第二期(读世界,已完成)**:copy / cut / replace / overlay / hollow / naturalize / smooth / trim、维护命令、mask、deform。
3. **第三期(生成器,已完成,regen 除外)**:记录用的世界,以及 forest / forestgen / flora / pumpkins / feature / structure;另补 snowsmooth、extinguish、morph、erode、dilate、raise、lower、size、count、distr。regen 不做,理由见"调原版生成器的"。

每一期做完都跑全套测试,全绿才算完;不留旧名字、不留第二份实现。
