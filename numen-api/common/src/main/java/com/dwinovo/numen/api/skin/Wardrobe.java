package com.dwinovo.numen.api.skin;

import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * 给同伴换皮肤在客户端的唯一一条流程:<b>拿到原图 → 查本机缓存 → 没签过就签 → 交给服务端穿上</b>。皮肤选择器与召唤卡都走这里,
 * 拿原图的办法不同(本机或服务器文件夹里的一张、网址、正版玩家),后面一模一样。各步各管各的:缓存是 {@link SkinCache}
 * (按图片内容哈希加手臂模型),签名是 {@link SkinSigning} 的队,交给服务端穿上是 {@link Dress}(服务端验过 Mojang 的签名才穿)。
 *
 * <p>手上已有图的({@link SkinSigning.Source.Image})先问缓存:签过的当场交出去,不排队;没签过的才进队。网址和借正版玩家的皮肤要到队里
 * 才取得到图,队里取到之后同样先问缓存。
 *
 * <p>线程:客户端主线程。
 */
public final class Wardrobe {

    /** 把签好的皮肤交给服务端穿上,或脱掉。服务端不收(验签没过、不是她的主人)时 {@code answer} 收到原因码({@link SkinError}),收下了是 {@code null}。 */
    public interface Dress {

        void wear(UUID companion, SkinRef ref, SkinProfile skin, Consumer<String> answer);

        void takeOff(UUID companion, Consumer<String> answer);
    }

    private final SkinSigning signing;
    private final SkinCache cache;

    public Wardrobe(SkinSigning signing, SkinCache cache) {
        this.signing = signing;
        this.cache = cache;
    }

    /**
     * 给她换上这张皮肤。
     *
     * @param name     进度和失败里叫它什么
     * @param variant  手臂模型;{@code null} 是看图自己判断
     * @param progress 排队的进度与结局;办成时服务端已经收下了(回调在服务端答复之后)
     * @return 排上队的编号;已经签过、当场交出去了是 {@code 0};排满了是 {@code -1}
     * @throws SkinError 给的图不能当皮肤用:当场拒绝,不排队
     */
    public long wear(UUID companion, String name, SkinSigning.Source source, String variant, Dress dress,
                     SkinSigning.Listener progress) {
        if (source instanceof SkinSigning.Source.Image image) {
            SkinImage.check(image.png());
            SkinRef ref = SkinRef.of(image.png(), variant != null ? variant : SkinVariants.infer(image.png()));
            SkinProfile known = cache.find(ref);
            if (known != null) {
                hand(companion, ref, known, dress, progress);
                return 0;
            }
        }
        return signing.submit(new SkinSigning.Request(name, variant, source), new SkinSigning.Listener() {
            @Override
            public void queued(int position) {
                progress.queued(position);
            }

            @Override
            public void waiting(int seconds) {
                progress.waiting(seconds);
            }

            @Override
            public void done(SkinSigning.Outcome outcome) {
                if (outcome.ok()) {
                    hand(companion, outcome.ref(), outcome.skin(), dress, progress);
                } else {
                    progress.done(outcome);
                }
            }
        });
    }

    /** 签好的交给服务端;它答了才算办完。 */
    private static void hand(UUID companion, SkinRef ref, SkinProfile skin, Dress dress, SkinSigning.Listener progress) {
        dress.wear(companion, ref, skin, problem -> progress.done(problem == null
                ? new SkinSigning.Outcome(ref, skin, null)
                : new SkinSigning.Outcome(null, null, SkinError.ofCode(problem))));
    }

    /** 在排的签名,按队里的先后。 */
    public List<SkinSigning.Pending> pending() {
        return signing.pending();
    }
}
