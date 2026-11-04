package com.dwinovo.numen.pathing.api;

/**
 * 这一趟用哪一类移动方式,由调用方说,寻路不按价钱替它选:
 * <ul>
 *   <li>{@link #WALK} 只走、游、挖、搭,从不飞:身体许飞也不排飞行走法、不排滑翔段(身体在飞,先降到地上);</li>
 *   <li>{@link #FLY} 只用创造飞行:进同一个 A*,到点悬停;身体不许飞就在受理时拒绝;</li>
 *   <li>{@link #GLIDE} 只用鞘翅:创造模式下也一样滑翔(身体在飞,先降到地上)。</li>
 * </ul>
 */
public enum Travel {
    WALK, FLY, GLIDE
}
