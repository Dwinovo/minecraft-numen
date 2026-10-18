package com.dwinovo.numen.api.client.agent;

import java.util.function.Consumer;

/**
 * 同一处反复发的请求(随草稿变化的目录问题、点一下就试一次的检测)只认最近发的那一个的答复:更早的答复晚到了,
 * 或者发请求的那个界面已经重开({@link #cancel}),它们的答复一概不交出去。库表里的内容照旧被每个答复刷新,这里只管"谁来处理答复"。
 */
public final class LatestReply {

    private int latest;

    /** 包一层要交给 {@link RemoteLibrary#request} 的处理:只有发出它之后再没发过新请求,它才会被调。 */
    public Consumer<RemoteLibrary.Reply> take(Consumer<RemoteLibrary.Reply> onReply) {
        int mine = ++latest;
        return reply -> {
            if (mine == latest) {
                onReply.accept(reply);
            }
        };
    }

    /** 此前发出的请求都不再算:界面重开了,旧答复不属于新的一轮。 */
    public void cancel() {
        latest++;
    }
}
