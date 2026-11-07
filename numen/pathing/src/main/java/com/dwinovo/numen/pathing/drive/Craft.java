package com.dwinovo.numen.pathing.drive;

import com.dwinovo.numen.api.entity.Controls;
import com.dwinovo.numen.api.entity.Neck;
import com.dwinovo.numen.pathing.plan.BodySnapshot;
import com.dwinovo.numen.pathing.plan.Mobility;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * 空中的身体,滑翔与创造飞行的飞行代码({@link Glide}、{@link Landings}、{@link Sortie}、{@link Helm})看到与动用的那部分:身体此刻的状态、世界、
 * 键盘与视角、放烟花。生产里是 {@link Rig} 包着的服务端玩家;单测里是照 {@link Dynamics} 逐刻推的模拟身体,所以飞行的决策不用起服务器就能整趟跑。
 */
interface Craft {

    /** 日志里的"谁"({@link PathLog#who})。 */
    String who();

    /** 身体此刻的状态,日志用({@link PathLog#body})。 */
    String describe();

    /** 身体此刻所在的世界。 */
    Terrain world();

    /** 此刻的身体快照(这一趟的口径,{@link Rig#travel})。 */
    BodySnapshot snapshot();

    /** 此刻身上的移动方式(每刻读,烟花一支支用掉)。 */
    Mobility mobility();

    Vec3 position();

    Vec3 velocity();

    /** 脚所在的格。 */
    BlockPos cell();

    /** 碰撞盒。 */
    AABB box();

    boolean onGround();

    /** 鞘翅展开着在滑翔。 */
    boolean gliding();

    float yaw();

    float pitch();

    double eyeHeight();

    double gravity();

    /** 攒着的坠落距离。 */
    double fallen();

    float health();

    /** 胸甲位的滑翔物已用掉的耐久。 */
    int wear();

    /** 身体的键盘。 */
    Controls keys();

    /** 视角转向 {@code yaw}、{@code pitch} 一刻(颈部模型,{@link com.dwinovo.numen.api.entity.Look#steer})。 */
    void steer(float yaw, float pitch);

    /** 颈部此刻的样子。 */
    Neck neck();

    /** 把烟花拿到手上(换手记进身体动作);拿在手上了为真。 */
    boolean holdRocket();

    /** 右键放一支烟花;放成了为真。先 {@link #holdRocket}。 */
    boolean launchRocket();

    /** 这一趟的账。 */
    EditLedger ledger();
}
