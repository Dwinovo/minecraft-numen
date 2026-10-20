package com.dwinovo.numen.api.skin;

import java.util.UUID;

/**
 * 给同伴换皮肤的唯一一条流程:<b>拿到原图 → 查缓存 → 没签过就签 → 套上</b>。面板(选了本机或服务器文件夹里的一张)和命令
 * ({@code player}、{@code url}、{@code server})都走这里,拿原图的办法不同,后面一模一样。各步各管各的:
 * 原图来自文件夹({@link SkinFolder})、网址或正版玩家({@link SkinSigning.Source});缓存是 {@link SkinCache}(按图片内容哈希加手臂模型);
 * 签名是 {@link SkinSigning} 的队;穿上是 {@link Dress}(换身体与记绑定,不在这里)。玩家看不到"签名"这回事,只看到进度和失败的原因。
 *
 * <p>手上已经有图的({@link #wear}、{@link #wearFile})先问缓存:签过的当场穿上,不排队;没签过的才进队。网址和借正版玩家的皮肤图要到队里
 * 才取得到,队里取到之后同样先问缓存。
 *
 * <p>线程:服务端主线程。
 */
public final class Wardrobe {

    /** 把一副皮肤穿到一只同伴身上,或脱掉。 */
    public interface Dress {

        /** 穿上并记下穿的是缓存里的哪一张;她已不在名册上(被遣散了)是 {@code false}。 */
        boolean wear(UUID companion, SkinRef ref, SkinProfile skin);

        /** 脱掉,回到默认皮肤。 */
        void takeOff(UUID companion);
    }

    private final SkinSigning signing;
    private final SkinCache cache;
    private final SkinFolder folder;
    private final Dress dress;

    /** @param folder 服务器上的皮肤文件夹("服务器提供") */
    public Wardrobe(SkinSigning signing, SkinCache cache, SkinFolder folder, Dress dress) {
        this.signing = signing;
        this.cache = cache;
        this.folder = folder;
        this.dress = dress;
    }

    /** 服务器上的皮肤文件夹。 */
    public SkinFolder folder() {
        return folder;
    }

    /**
     * 给她换上服务器文件夹里叫这个名字的皮肤。
     *
     * @throws SkinError 没有这张图、不能当皮肤用
     */
    public long wearFile(UUID owner, UUID companion, String file, String variant, SkinSigning.Listener progress) {
        return wear(owner, companion, file, new SkinSigning.Source.Image(folder.read(file)), variant, progress);
    }

    /**
     * 给她换上这张皮肤。
     *
     * @param name    进度和失败里叫它什么
     * @param variant 手臂模型;{@code null} 是看图自己判断
     * @param progress 排队与结局;办成时她已经穿上了(回调在她穿上之后)
     * @return 排上队的编号;已经签过、当场穿上了是 {@code 0};这个人在排的已满是 {@code -1}
     * @throws SkinError 上传的图不能当皮肤用:当场拒绝,不排队
     */
    public long wear(UUID owner, UUID companion, String name, SkinSigning.Source source, String variant,
                     SkinSigning.Listener progress) {
        if (source instanceof SkinSigning.Source.Image image) {
            SkinImage.check(image.png());
            SkinRef ref = SkinRef.of(image.png(), variant != null ? variant : SkinVariants.infer(image.png()));
            SkinProfile known = cache.find(ref);
            if (known != null) {
                dress.wear(companion, ref, known);
                progress.done(new SkinSigning.Outcome(ref, known, null));
                return 0;
            }
        }
        return signing.submit(new SkinSigning.Request(owner, name, variant, source), new SkinSigning.Listener() {
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
                    dress.wear(companion, outcome.ref(), outcome.skin());
                }
                progress.done(outcome);
            }
        });
    }

    /** 脱掉她的皮肤。 */
    public void takeOff(UUID companion) {
        dress.takeOff(companion);
    }
}
