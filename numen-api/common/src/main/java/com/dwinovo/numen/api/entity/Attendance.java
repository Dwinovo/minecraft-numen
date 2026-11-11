package com.dwinovo.numen.api.entity;

/**
 * 她此刻该不该在世界里——<b>判据只在这一处</b>。纯函数,不碰 Minecraft,所以能单测;读名册与玩家表、把世界修到该有的样子是
 * {@link Companions#reconcile} 的事,所有时机(登入登出、外部大脑连上断开、来访开始结束、下线命令、召唤)都只调它。
 *
 * <pre>
 * 该在线 = 名册里有她  且  主人在场({@code OwnerPresence.present})  且  没有被留在外面
 * </pre>
 */
public final class Attendance {

    private Attendance() {}

    /** 她被留在外面的原因:{@link #OWNER} 主人下令下线;{@link #SERVER} 服务器把她请出去了(踢人、插件、不接纳她)。 */
    public enum Why { NONE, OWNER, SERVER }

    /**
     * "留在外面"这一个状态与它的原因。留在外面的她等主人再召唤才回来,期间谁也不替主人把她带回来。{@code reason} 是服务器请出去时说的理由,
     * 主人下令的没有。
     */
    public record Outside(Why why, String reason) {

        /** 没有被留在外面。 */
        public static final Outside NONE = new Outside(Why.NONE, "");

        public static Outside byOwner() {
            return new Outside(Why.OWNER, "");
        }

        public static Outside byServer(String reason) {
            return new Outside(Why.SERVER, reason == null ? "" : reason);
        }

        /** 被留在外面,不论为什么。 */
        public boolean isOutside() {
            return why != Why.NONE;
        }

        /** 是服务器请出去的。 */
        public boolean server() {
            return why == Why.SERVER;
        }
    }

    /** 世界该往哪边修:不动、带回来、登出。 */
    public enum Move { STAY, BRING, LOG_OUT }

    /**
     * 她该在线的话,不在世界里就带回来;不该在线的话,在世界里就登出;已经是该有的样子就不动。
     *
     * @param outside      名册上她被留在外面的状态
     * @param ownerPresent 主人在不在场
     * @param inWorld      她的身体此刻在不在世界里
     */
    public static Move move(Outside outside, boolean ownerPresent, boolean inWorld) {
        boolean shouldBeOnline = ownerPresent && !outside.isOutside();
        if (shouldBeOnline == inWorld) {
            return Move.STAY;
        }
        return shouldBeOnline ? Move.BRING : Move.LOG_OUT;
    }
}
