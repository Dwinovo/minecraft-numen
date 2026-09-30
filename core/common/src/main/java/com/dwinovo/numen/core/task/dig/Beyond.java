package com.dwinovo.numen.core.task.dig;

import com.dwinovo.numen.area.AreaRef;
import com.dwinovo.numen.area.Cells;
import com.dwinovo.numen.core.nav.NavText;
import com.dwinovo.numen.core.nav.WorkArea;

import net.minecraft.core.BlockPos;

/**
 * 要挖的格里落在工作区外的那些:{@code work dig} 只报告、不去。回执里说它们的那一句——还有几格、最近一格在哪、离她多远,以及
 * 照抄就能做的开路写法——只在这里写;受理时要挖的格整个落在区外、当场拒收的那句话也在这里。
 *
 * <p>开路的写法两种都给:先规划一条路线过目再走({@code route new … --arrive dig --alter natural}、{@code route plan}、
 * {@code move go}),或者一行直接走过去({@code move_goto … arrive:dig alter:natural})。点名的是一块区域(或它的一部分)时去处写
 * 那块区域,她从离得最近的那一侧够过去;点名的是坐标、或几块区域,去处写最近那一格的坐标。
 *
 * @param cells 区外的格
 * @param area  点名的只是一块区域(或它的一部分)时是它,开路的去处就写它;否则为 null
 * @param what  回执里怎么称呼要挖的东西({@code ores/g3}、{@code 10,64,5})
 * @param again 再挖一次时照抄的那一截参数({@code ores/g3}、{@code 10 64 5});另一件活派下的挖掘没有这一行,为 null
 */
public record Beyond(Cells cells, AreaRef area, String what, String again) {

    /** 路线的名字:点名一块区域时借它的名字,否则叫 {@code dig}。 */
    private static final String ROUTE = "dig";

    public boolean isEmpty() {
        return cells.isEmpty();
    }

    /**
     * {@code 4 cells of ores/g3 lie beyond it and are left, the nearest at 80,40,-10, about 69 blocks from me. To dig
     * them, open the way first: …}。
     *
     * @param from 她此刻脚下那一格
     */
    public String told(BlockPos from) {
        BlockPos near = cells.nearest(from);
        return cells.size() + " cell(s) of " + what + " lie beyond it and are left, the nearest at " + coords(near)
                + ", about " + blocks(from, near) + " blocks from me. " + openTheWay(near);
    }

    /**
     * 要挖的格整个落在区外:受理当场拒收的那句话。
     *
     * @param work 她此刻的工作区
     */
    public String refusal(WorkArea work) {
        BlockPos near = cells.nearest(work.center());
        return "all " + cells.size() + " cell(s) of " + what + " lie beyond my work area (" + work.describe()
                + "), so I did not start; the nearest is at " + coords(near) + ", about " + blocks(work.center(), near)
                + " blocks away. " + openTheWay(near);
    }

    /** 照抄就能做的开路:规划一条路线再走,或一行直接走过去;到了再挖。另一件活派下的挖掘没有这一句,是空串。 */
    String openTheWay(BlockPos near) {
        if (again == null) {
            return "";
        }
        String to = area != null ? area.toString() : near.getX() + " " + near.getY() + " " + near.getZ();
        String route = area != null ? area.name() : ROUTE;
        String go = area != null ? NavText.gotoCall(area, "arrive:dig alter:natural")
                : NavText.gotoCall(near, "arrive:dig alter:natural");
        return "To dig there, open the way first: `route new " + route + " --to " + to + " --arrive dig --alter natural`, "
                + "`route plan " + route + "` to see what the way changes, `move go " + route + "` — or straight away "
                + go + " — then `work dig " + again + "` again.";
    }

    private static String coords(BlockPos p) {
        return p.getX() + "," + p.getY() + "," + p.getZ();
    }

    private static long blocks(BlockPos from, BlockPos to) {
        return Math.round(Math.sqrt(from.distSqr(to)));
    }
}
