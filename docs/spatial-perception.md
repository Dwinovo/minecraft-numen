# 空间感知：把体素世界喂给 LLM 的表征设计

> 设计文档。目标：让驱动同伴的 LLM 具备"空间感"——理解周围地形/障碍/落差/危险的排布，而不是靠逐格 `inspect_block` 盲戳。

## 1. 问题

同伴的世界感知原本只有**扁平坐标列表**：
- `scan_blocks` → 按方块 id 过滤的 `[{x,y,z,block,distance}]`（还有上限截断）；
- `inspect_block` → 单格属性探测。

实测日志里单次任务出现 **38 次 `inspect_block`** —— LLM"看不见"空间，只能一格一格戳来重建几何。大模型恰恰最不擅长从坐标元组列表里重建 3D 结构：无相对/拓扑关系、无可视化、token 重。

## 2. 结论（两份调研）

### 2.1 LLM × Minecraft 现状：几乎没人给 LLM 真正的空间地图
- **Voyager**（Mineflayer + GPT-4）：喂 8×2×8 范围内的**去重方块名集合**，连坐标都丢，空间推理**外包给 pathfinder**——LLM 没有空间感，是被绕开的。[action_template](https://github.com/MineDojo/Voyager/blob/main/voyager/prompts/action_template.txt)
- **Mindcraft**：去重方块名 + `Block Below/Legs/Head` 相对标签 + 显式坐标；试过视觉，"无显著提升"。[world.js](https://github.com/mindcraft-bots/mindcraft/blob/main/src/agent/library/world.js)
- **GITM / JARVIS-1 / Odyssey**：纯文本符号。**没有一个用 ASCII 剖面 / 俯视网格 / 高度图。**

### 2.2 通用空间推理研究：agent 中心的字符网格远胜坐标与图像
- **STMR / Aerial-VLN**（Gao et al., arXiv:2410.08500）：以 agent 为中心的 **20×20 语义字符矩阵**（每格 semantic max-pooling）。GPT-4o 实测：喂原始 RGB 俯视图 **SR 1.1%**，换成 STMR 文本矩阵 **SR 15.0%**（≈13.6×）。关键消融（纯文本 vs 纯文本）：拓扑文本图 4.9% < 方向距离描述 6.1% < **网格矩阵 15.0%**——赢的是"网格格式本身"。[arXiv:2410.08500](https://arxiv.org/abs/2410.08500)
- **Visualization-of-Thought**（微软, arXiv:2404.03622）：让 LLM 在推理时**自己画 ASCII 网格**追踪空间状态，导航 +27%、位置追踪率 +78.5%（达 98.5%），超过喂文本+图像的多模态模型。

### 2.3 自动驾驶（最成熟的 egocentric 网格领域）
- **BEV（鸟瞰图）**：自我中心、度量一致、语义分层。核心哲学正是我们要的。
- **Occupancy grid / 占用网络**（Occ3D、TPVFormer、SliceOcc）：BEV 拍平高度的致命伤 → 用 **3D 体素占用**补竖直；但对 LLM 太重，降维法是**少数几张水平切片**（Occ3D 水平 200×200 却只 16 层竖直）。→ **竖直性用"少层切片"补，不用高度图（表达不了悬挑/头顶净空）、不用稠密体素（过重）。**
- **Costmap（ROS Nav2）**：喂规划器的是**每格代价 + 分层合成 + 危险膨胀（inflation）+ egocentric 滚动窗口**。→ 喂 LLM 的应是**语义/代价**而非原始方块 id；对危险格做**膨胀缓冲**让模型天然远离岩浆边缘。
- **喂 LLM 的格式**：稀疏对象（车/障碍）用**结构化列表 + 坐标**（GPT-Driver、Talk2BEV）；稠密局部几何用**紧凑 ASCII 网格 + VoT 草稿**。二者分工。[GPT-Driver](https://arxiv.org/abs/2310.01415) · [Nav2 costmap](https://docs.nav2.org/configuration/packages/configuring-costmaps.html)

## 3. `look_around` 工具设计

**定位**：AD"稠密网格 + 稀疏列表"分工里的**稠密近场网格**那半。稀疏远物（矿/箱/怪）交给已有的 `scan_blocks` / `scan_nearby_entities`。

**返回**：一整块纯文本——自我中心俯视字符网格（`@` 居中，N 朝上，E 朝右，1 格=1 方块）+ 图例 + 路由提示。默认半径 8（17×17），可选 4–16。

**每格语义 pooling 成一个移动可达性符号**（把 AD"少层切片"塌成一个字符）：

| 符号 | 含义 |
|---|---|
| `@` | 你（正中） |
| `.` | 平地可走（同层） |
| `^` | 上一级台阶（可跳上） |
| `,` | 下 1–2 格可走 |
| `v` | 掉落 ≥3（坑/崖） |
| `#` | 墙/挡住（上台阶 ≥2 或身位被挡） |
| `~` | 水 |
| `!` | 岩浆/危险 |
| `x` | 危险缓冲（岩浆邻格，抄 costmap inflation） |
| `T` | 树（原木/树叶阻挡） |
| `?` | 未加载 |

**取用现成能力**：世界读取 `LoadedOnlyView`（未加载=`?`，绝不同步生成）；每格由 `MovementHelper.canWalkOn / fullyPassable / isLava` 真算（与寻路同口径）；圆心用寻路口径 `PathExecutor.playerFeet`。

**VoT 引导**：图例末尾提示"逐格 trace 路径"，鼓励模型在网格上做 Visualization-of-Thought 式推理。

## 4. 待办（后续）
- **近细远粗多分辨率**（AD 的 hybrid local multiresolution grid）：近处 1 格、远处 4–8 格聚合成一格，用很少 token 把视野撑远。当前窗口够小，暂缓。
- **精细竖直**：若移动决策需要更细的坡度/多层净空，可加第二层切片或相对高度网格（谨慎，避免退化成被否掉的高度图）。
- 归档的两份调研全文见工程记录（Minecraft-LLM 表征 / 自动驾驶空间表征）。

## 参考
- Gao, Wang, Han, Jing, Wang, Zhao. *Exploring Spatial Representation to Enhance LLM Reasoning in Aerial Vision-Language Navigation*. arXiv:2410.08500, 2024.
- *Visualization-of-Thought Elicits Spatial Reasoning in Large Language Models*. arXiv:2404.03622.
- Tian et al. *Occ3D: A Large-Scale 3D Occupancy Prediction Benchmark*. arXiv:2304.14365.
- ROS Nav2 `costmap_2d`：https://docs.nav2.org/configuration/packages/configuring-costmaps.html
- Voyager：https://github.com/MineDojo/Voyager ・ Mindcraft：https://github.com/mindcraft-bots/mindcraft
