# numen.shape 对齐 WorldEdit

状态:设计稿,待用户拍板(10-27)。WorldEdit 源码:`D:\01_Projects\WorldEdit`(提交 8aa2205),下文 `ES` 指 `worldedit-core/.../EditSession.java`。

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

### 生成(返回 Cells;pattern 省略时返回选区)

| Numen | WorldEdit | 语义要点(以 WE 源码为准) |
|---|---|---|
| `cyl(at, pattern, radii, height)` / `hcyl` | `//cyl` `//hcyl` | 底面中心在 at;radii 一个或两个(x、z),可小数;判据 `x²/rx²+z²/rz² ≤ 1`,r 先 +0.5(ES:1762);height 负数向下;半径下限 1 |
| `sphere(at, pattern, radii, raised)` / `hsphere` | `//sphere` `//hsphere` | radii 一个或三个(x、y、z);r 先 +0.5;`raised` 即 `-r`,中心上移 (int)ry |
| `cone(at, pattern, radii, height, thickness)` | `//cone` | 半径**不** +0.5(ES:1845) |
| `pyramid(at, pattern, size)` / `hpyramid` | `//pyramid` `//hpyramid` | 共 size 层,底边 2·size−1;空心版只留外圈、无底 |
| `line(points, pattern, thickness)` / `hline` | `//line`(含 `-h`) | 两点或折线;thickness 是球半径,不 +0.5(ES:2903) |
| `curve(points, pattern, thickness)` / `hcurve` | `//curve` | Kochanek-Bartels 样条,参数全 0 |
| `generate(region, pattern, fn, mode)` | `//generate` | WE 的表达式换成 Lua 函数 `fn(x,y,z) → boolean`;`mode` 对应 WE 的缺省归一 −1..1 / `-r` 世界坐标 / `-o` 放置点为原点 / `-c` 选区中心不缩放 |
| `cuboid(a, b)` | 选区 cuboid | 只出选区,用 `:set` 等上方块 |
| `poly(points, minY, maxY)`、`ellipsoid(c, radii)`、`convex(points)` | 选区 poly2d / ellipsoid / convex | 判据见 `regions/Polygonal2DRegion.java:344`、`EllipsoidRegion.java:185` |

### 选区上的操作(纯几何)

| Numen | WorldEdit | 要点 |
|---|---|---|
| `r:set(pattern)` | `//set` | 每格放 pattern |
| `r:walls(pattern)` | `//walls` | 长方体:四侧不含顶底;别的形状:去掉上下两层的壳(ES:1386) |
| `r:faces(pattern)`(`r:outline`) | `//faces` | 六面 / 6 邻接的壳 |
| `r:center(pattern)` | `//center` | 偶数长的轴放 2 格,共 1/2/4/8 格 |
| `c:stack(count, dir, opts)` | `//stack` | count 不含原件;位移 = k·dir·外包盒尺寸;`opts.skipAir`(`-a`);`opts.cells`(`-r` 按格计) |
| `c:move(count, dir, leave)` | `//move` | 原处 = 源减目标,填 leave(缺省 air) |
| `c:paste(at)` | `//paste` | 剪贴板原点对到 at |
| `c:rotate(y, x, z)` | `//rotate` | 度数,正数 = 从上看顺时针;绕剪贴板原点;本期只支持 90° 倍数(见第三节) |
| `c:flip(dir)` | `//flip` | 绕原点那一格镜像 |
| `c:revolve(count)` | `//revolve` | 绕竖直轴均匀转 count 份 |

### 读世界的操作(读,不写;读的结果仍是 Cells)

| Numen | WorldEdit | 读什么 |
|---|---|---|
| `copy(region, mask)` | `//copy` | 选区里的方块;原点 = 她当时站的格 |
| `c:cut(leave)` | `//cut` | 同 copy,另出原处填 leave 的格 |
| `r:replace(mask, pattern)` | `//replace` | 选区每格当前方块;mask 缺省 `#existing` |
| `r:overlay(pattern)` | `//overlay` | 每列顶上第一个非空气格;可能到 maxY+1;顶上已有方块的列跳过 |
| `r:hollow(thickness, pattern)` | `//hollow` | 每格挡不挡移动;从边界泛洪找外部,曼哈顿距离 ≥ thickness 的换成 pattern(缺省 air)。边界全是实心时会整片挖空,这是 WE 的行为,照搬并在说明里写清 |
| `r:naturalize()` | `//naturalize` | 每列的草、泥土、石头 |
| `r:smooth(iterations)` | `//smooth` | 高度图,高斯(5, 1.0) |

读世界只读已加载的区块;选区里有没加载的格就整个调用失败,并说出是哪一片。

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
  - 蒙版测的是世界里的方块,所以只出现在读世界的操作上。
- 随机照 WE:每格单独掷、不带种子。`Cells` 是一个值,算一次存进变量,`diff` 和 `place` 收的是同一份,所以不需要另加种子。

### 选区编辑(照 WE 按形状)

`r:expand` / `r:contract`(含 `vert`)、`r:shift`、`r:outset` / `r:inset`(`-h` 只水平、`-v` 只竖直),各形状的语义照 `SelectionCommands.java:392/429/458/478`、`ExpandCommands.java:124`:例如圆柱只能竖直扩、椭球改半径。`r:trim(mask)` 读世界。

### 调原版生成器的(读世界 → 格)

`forest` `flora` `forestgen` `pumpkins` `feature` `structure` `regen`:让原版的地物或区块生成器跑在一个"记录用的世界"上,读真实世界、把它要写的方块记下来,交回格。这样产出就是原版生成器真正会放的东西,模组的地物也一样,不自己仿写。

### 以世界为准的维护命令(读世界 → 格)

`drain` `fixwater` `fixlava` `snow` `thaw` `green` `removeabove` `removebelow` `removenear` `replacenear` `fill` `fillr`,以及笔刷才有的 `gravity` `splatter` `heightmap`:都按 WE 的判据读世界、算出要放(或挖成空气)的格。`deform` 和 `^` 改状态 pattern 一样做。

### 不做的,及原因

只有本质上不是"放方块"的:
- **生物群系命令**(`//setbiome` 等):改的是群系不是方块,`place` 放不了。
- **历史**(`//undo` `//redo`):她不做隐形撤销,每次放下都如实回执。要恢复,就在动手前 `copy` 一份,之后再放回去。
- **schematic 文件**(`//schem`):蓝图文件归 Litematica 插件。
- **选区魔杖、笔刷绑定**:都是"把操作绑到物品上、对准哪就在哪做"的玩家界面。她直接在某个位置调操作,笔刷能做的操作都已在上面。

### Numen 自己有、WE 没有的,保留

`union`、`minus`、字符网格 `layer`、`Pos` 运算、`cells(list)`。这些是组合用的原语,WE 没有对应物,叫法不变。

## 三、要改掉的现状

1. **空心圆柱和空心球的定义改成 WE 的**:向外一步(+x、+z,球还有 +y)出界才留下,是最薄的连续壳(ES:1801、1968)。现在用的距离带,r=4 的空心球多出 24 格。`BuildGameTests` 里空心塔筒那条用例的判据随定义一起改。
2. **方块状态的旋转和翻转只认原版**,删掉 `shape.lua` 里手写的 `turned()`。
   - 原版每种方块自己实现了 `BlockState.rotate(Rotation)` 和 `mirror(Mirror)`。
   - 铁轨的 `shape`、合成器的 `orientation`、门的 `hinge`、楼梯的内外角,以及模组方块,它们都自己处理。
   - 现在的 Lua 版是这份知识的一个残缺副本:铁轨转了不变,带 NBT 的名字会静默跳过。这正是"多源"。
   - 竖直翻转原版没有,按 WE 的规则只处理原版属性:`half`、台阶 `type` 上下互换、`facing` 上下互换、铁轨 `ascending_*` 反向。
   - 绕 X/Z 轴旋转和非 90° 旋转本期不做。传了就报错说明,不插值、不静默。
3. **转心照 WE 用剪贴板原点。** Cells 带一个 `origin`:
   - `copy` 记她当时站的格;
   - 生成函数记放置点 `at`;
   - `shift`、`paste` 跟着移动。
   现在缺省绕世界原点 (0,0,0) 转,结果离工地几十万格,改掉。
4. **改名一次改完,不留别名**:
   - `box` 改为 `cuboid(a, b):set(...)` 或 `:faces(...)`;
   - `cylinder` 改为 `cyl` / `hcyl`;
   - `hollow` 参数改为 h 前缀的函数;
   - `rotate(quarters)` 改为 `rotate(度数)`。
   技能、bench 解法、测试、提示词里的旧名字同一批改掉。

## 四、实现分工

- **几何留在 Lua 模块 `numen.shape`**:模型能读它的源码,方法也挂在 `Cells` 上,和现在一样。
- **Lua 做不了的,由 Java 提供少量原语,Lua 调用**:
  - 方块状态的旋转和翻转(原版 `rotate`、`mirror`);
  - pattern 解析(标签、随机状态、`/setblock` 写法校验,原版 `BlockStateParser`);
  - 读世界(一片格的当前方块、每格是否挡移动、每列高度)。
  每件只有一个实现,Lua 不再自己猜方块状态。
- 上限仍是 `LuaCodecs.MAX_CELLS` = 16384,超了报错并说明怎么拆。

## 五、验证

- **单测**:每个生成函数在若干半径上的格集合,对照 WE 的判据重算,包括空心壳的格数。pattern 的权重分布。旋转和翻转对楼梯、铁轨、门、合成器的状态,以原版 `rotate`、`mirror` 的结果为准。
- **GameTest**:读世界的四件(copy、replace、overlay、hollow)各一条,在代码搭的场景里断言交回的格;再加一条"copy → rotate → paste → place"建出来和原物转过的样子逐格一致。
- **不跑 bench**:场景由用户统一安排。

## 六、分期

1. **第一期(纯几何)**:Region 及选区编辑、生成函数、选区操作、pattern、旋转和翻转改走原版、改名。
2. **第二期(读世界)**:copy / cut / replace / overlay / hollow / naturalize / smooth / trim、维护命令、mask、deform。
3. **第三期(生成器)**:记录用的世界,以及 forest / flora / feature / structure / regen 这些。

每一期做完都跑全套测试,全绿才算完;不留旧名字、不留第二份实现。
