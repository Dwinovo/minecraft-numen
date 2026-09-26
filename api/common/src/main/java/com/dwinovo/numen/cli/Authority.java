package com.dwinovo.numen.cli;

/**
 * 第 1 层的一个动作以谁的权威执行。只有两种,登记时在动作上声明({@link Action#authority}),不声明就是她自己的:
 *
 * <ul>
 *   <li>{@link #HERS}:她自己的。处理函数动她的身体、读世界;身体对世界的每个动作照常由权限层按动作裁决。
 *       她想执行原版或模组的指令,自己写 {@code /} 那一行就是,不经处理函数。</li>
 *   <li>{@link #SERVER_ON_HER}:服务器的权威,作用对象写死为她。给包装模组管理指令的动作用——那些指令能作用于任何人,
 *       她自己又没有那个权限等级。处理函数经 {@link ServerSource#onHer()} 拿到唯一的执行途径 {@link OnHer},它只让
 *       写目标前后的那两截,目标由它写成她。</li>
 * </ul>
 *
 * <p>权威只在这一处声明,处理函数不另开后门:动作的帮助写明它借了服务器的权威({@link CommandHelp#action})。
 */
public enum Authority {
    HERS,
    SERVER_ON_HER
}
