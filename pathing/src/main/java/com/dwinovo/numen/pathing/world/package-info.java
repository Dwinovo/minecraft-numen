/**
 * 第 0 层 地形模型:身体在一格世界里能怎样,全部从方块碰撞箱推导,规划、执行与感知读同一份。
 *
 * <ul>
 *   <li>{@link com.dwinovo.numen.pathing.world.Footing} —— 脚落在多高、踩的是哪一格、脚的高度归到哪一格(唯一规则);</li>
 *   <li>{@link com.dwinovo.numen.pathing.world.Clearance} —— 站立或潜行的身体放不放得下、占着哪几格、挡着它的是哪几格;</li>
 *   <li>{@link com.dwinovo.numen.pathing.world.Stepping} —— 走进相邻一列是走过去、要跳还是过不去,走出边沿落到多高;</li>
 *   <li>{@link com.dwinovo.numen.pathing.world.Reach} —— 够不够得着一格;</li>
 *   <li>{@link com.dwinovo.numen.pathing.world.Faces} —— 放方块时能点哪些面、点在哪;</li>
 *   <li>{@link com.dwinovo.numen.pathing.world.Replaceable} —— 放下的方块真正落在哪一格;</li>
 *   <li>{@link com.dwinovo.numen.pathing.world.Semantics} —— 碰撞箱表达不了的语义(流体、攀爬、门、危险、落沙、机关、易碎),
 *       碰撞箱随世界或身体变化的方块,以及脚下方块的起跳与步速系数、眼睛泡没泡在水里;</li>
 *   <li>{@link com.dwinovo.numen.pathing.world.Bounds} —— 世界边界;</li>
 *   <li>{@link com.dwinovo.numen.pathing.world.BodyStats} —— 身体的尺寸、迈步高度、起跳、交互距离、细雪托不托得住它,由宿主交进来。</li>
 * </ul>
 *
 * <p>这一层只读世界({@code BlockGetter}),不接触实体;按方块状态缓存的表只读、线程安全,搜索线程可以直接用。
 */
package com.dwinovo.numen.pathing.world;
