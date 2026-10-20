package com.dwinovo.numen.api.skin;

import com.dwinovo.numen.api.Constants;
import com.dwinovo.numen.api.entity.MojangSkins;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 皮肤签名的唯一一条路:整台服务器的签名请求排成一队,一个工作线程一个一个发。图片可以是玩家上传的,也可以是服务端按网址
 * 取来的({@link SkinImage#fetch}),或者借一个正版玩家的皮肤({@link MojangSkinLookup},那条本来就是 Mojang 签好的,不经 MineSkin)。
 * 拿到原图之后先问签名缓存({@link SkinCache},按图片内容哈希加手臂模型):签过的直接用,不联网;没签过的才发给 MineSkin,
 * MineSkin 限流了就等它说的那么久再发同一张,等的过程在 {@link Pending#state} 上看得见;排在后面的人看得到自己排第几。
 * 签好的结果存进缓存:签一次存永久,之后召唤和换肤不再联网。把结果穿到谁身上不是这里的事,由调用方在 {@link Listener#done} 里做。
 *
 * <p>线程:工作线程只做网络和读缓存;写缓存、通知玩家都交给 {@code main}(服务端主线程)。队列的状态任何线程可读。
 */
public final class SkinSigning implements AutoCloseable {

    /** 一个人最多同时有几张在排(含正在签的):再多就是占着队不放。 */
    public static final int MAX_PENDING_PER_OWNER = 5;
    /** MineSkin 限流却没说等多久时等多少秒。 */
    static final int DEFAULT_WAIT_SECONDS = 30;
    /** 一张图被限流最多总共等多少秒,超了就把限流当作失败交回。 */
    static final int MAX_WAIT_SECONDS = 10 * 60;

    /** 图片从哪来。 */
    public sealed interface Source {
        /** 玩家上传的图。 */
        record Image(byte[] png) implements Source {}

        /** 服务端按这个网址去取。 */
        record Url(String url) implements Source {}

        /** 借这个正版玩家的皮肤。 */
        record Player(String name) implements Source {}
    }

    /**
     * 一次签名要做的事。
     *
     * @param name    界面和提示里叫它什么(文件名、玩家名、网址)
     * @param variant 手臂模型;{@code null} 是看图自己判断({@link SkinVariants}),借正版玩家的皮肤时以它自己的为准
     */
    public record Request(UUID owner, String name, String variant, Source source) {}

    /** 一次签名的结局:成了是签好的皮肤和它在缓存里的名字,没成是原因。 */
    public record Outcome(SkinRef ref, SkinProfile skin, SkinError problem) {

        public boolean ok() {
            return problem == null;
        }
    }

    /** 一个人发起的签名,排队的进度和结局都从这里听。回调都在主线程。 */
    public interface Listener {

        /** 刚排上队:前面还有人时排第几(从 1 起,1 就是下一个);马上开始签是 1。 */
        default void queued(int position) {}

        /** 被 MineSkin 限流了,{@code seconds} 秒后再发同一张。 */
        default void waiting(int seconds) {}

        void done(Outcome outcome);
    }

    /** 排队中的一张的状态。 */
    public enum State { QUEUED, SIGNING, WAITING }

    /**
     * 一张在队里或刚失败的签名,界面照它画一行。失败的留在这里直到玩家撤掉它({@link #cancel})。
     *
     * @param position 排第几(从 1 起,1 是正在签或下一个);失败的是 0
     * @param failure  失败的原因;还在排着是 {@code null}
     */
    public record Pending(long id, UUID owner, String name, State state, int position, SkinError failure) {}

    /** 网络上的四件事,单测换成假的。 */
    public interface Backend {

        MineSkinClient.Signed sign(byte[] png, String variant, String apiKey);

        byte[] fetch(String url);

        MojangSkinLookup.Result lookup(String playerName);

        byte[] texture(String url);

        Backend NETWORK = new Backend() {
            @Override
            public MineSkinClient.Signed sign(byte[] png, String variant, String apiKey) {
                return MineSkinClient.generate(png, variant, apiKey);
            }

            @Override
            public byte[] fetch(String url) {
                return SkinImage.fetch(url);
            }

            @Override
            public MojangSkinLookup.Result lookup(String playerName) {
                return MojangSkinLookup.lookup(playerName);
            }

            @Override
            public byte[] texture(String url) {
                return SkinImage.fetchTexture(url);
            }
        };
    }

    /** 等待:单测换成不真等的。 */
    public interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    private static final class Job {
        final long id;
        final Request request;
        final Listener listener;
        volatile State state = State.QUEUED;
        volatile boolean cancelled;

        Job(long id, Request request, Listener listener) {
            this.id = id;
            this.request = request;
            this.listener = listener;
        }
    }

    private final Backend backend;
    private final Supplier<String> apiKey;
    private final Executor main;
    private final Consumer<UUID> changed;
    private final SkinCache cache;
    private final Sleeper sleeper;
    private final AtomicLong ids = new AtomicLong();
    private final LinkedList<Job> queue = new LinkedList<>();
    private final Map<UUID, List<Pending>> failures = new LinkedHashMap<>();
    private final Thread worker;
    private boolean closed;

    /**
     * @param apiKey  服主填的 MineSkin API key(每次发之前现取,改了立即生效);没有返回 {@code null}
     * @param main    服务端主线程
     * @param changed 某个人看到的队变了(排位、状态、结局),该把新的表推给他
     * @param cache   签好的存这里;签之前先问它
     */
    public SkinSigning(Backend backend, Supplier<String> apiKey, Executor main, Consumer<UUID> changed,
                       SkinCache cache, Sleeper sleeper) {
        this.backend = backend;
        this.apiKey = apiKey;
        this.main = main;
        this.changed = changed;
        this.cache = cache;
        this.sleeper = sleeper;
        this.worker = new Thread(this::run, "numen-skin-signing");
        this.worker.setDaemon(true);
        this.worker.start();
    }

    /**
     * 排一张。
     *
     * @return 排上了的编号;这个人在排的已满 {@link #MAX_PENDING_PER_OWNER} 张是 {@code -1}
     * @throws SkinError 上传的图不能当皮肤用:当场拒绝,不排队
     */
    public long submit(Request request, Listener listener) {
        if (request.source() instanceof Source.Image image) {
            SkinImage.check(image.png());
        }
        Job job;
        int position;
        synchronized (this) {
            long mine = queue.stream().filter(j -> j.request.owner().equals(request.owner())).count();
            if (mine >= MAX_PENDING_PER_OWNER) {
                return -1;
            }
            job = new Job(ids.incrementAndGet(), request, listener);
            queue.add(job);
            position = queue.size();
            notifyAll();
        }
        main.execute(() -> {
            listener.queued(position);
            changed.accept(request.owner());
        });
        return job.id;
    }

    /** 这个人的在排的与失败的,在排的按队里的先后。 */
    public synchronized List<Pending> pendingOf(UUID owner) {
        List<Pending> out = new ArrayList<>();
        int position = 0;
        for (Job job : queue) {
            position++;
            if (job.request.owner().equals(owner)) {
                out.add(new Pending(job.id, owner, job.request.name(), job.state, position, null));
            }
        }
        out.addAll(failures.getOrDefault(owner, List.of()));
        return out;
    }

    /**
     * 撤掉一张:还在排的从队里拿掉(已经在签的,结果出来就丢掉,不写库);失败的只是不再显示。
     *
     * @return 这个人名下确有这张
     */
    public boolean cancel(UUID owner, long id) {
        synchronized (this) {
            Iterator<Job> jobs = queue.iterator();
            while (jobs.hasNext()) {
                Job job = jobs.next();
                if (job.id == id && job.request.owner().equals(owner)) {
                    job.cancelled = true;
                    if (job.state == State.QUEUED) {
                        jobs.remove();
                    }
                    announce(owner);
                    return true;
                }
            }
            List<Pending> failed = failures.get(owner);
            if (failed != null && failed.removeIf(p -> p.id() == id)) {
                announce(owner);
                return true;
            }
        }
        return false;
    }

    @Override
    public void close() {
        synchronized (this) {
            closed = true;
            notifyAll();
        }
        worker.interrupt();
    }

    // ---- 工作线程 ----

    private void run() {
        while (true) {
            Job job;
            synchronized (this) {
                while (queue.isEmpty() && !closed) {
                    try {
                        wait();
                    } catch (InterruptedException interrupted) {
                        return;
                    }
                }
                if (closed) {
                    return;
                }
                job = queue.getFirst();
                job.state = State.SIGNING;
                announceAll();
            }
            Signed signed = null;
            SkinError problem = null;
            try {
                signed = sign(job);
            } catch (RuntimeException e) {
                problem = SkinError.fromError(e);
                Constants.LOG.warn("[numen-skin] 给 {} 签名失败: {}", job.request.name(), problem.getMessage());
            }
            if (Thread.currentThread().isInterrupted()) {
                return;
            }
            finish(job, signed, problem);
        }
    }

    /** 工作线程签出来的:缓存键与签好的皮肤;{@code fresh} 是这一次新签的(缓存里原来没有)。 */
    private record Signed(SkinRef ref, SkinProfile skin, boolean fresh) {}

    private void finish(Job job, Signed signed, SkinError problem) {
        synchronized (this) {
            queue.remove(job);
            if (problem != null && !job.cancelled) {
                failures.computeIfAbsent(job.request.owner(), owner -> new ArrayList<>())
                        .add(new Pending(job.id, job.request.owner(), job.request.name(), State.QUEUED, 0, problem));
            }
            announceAll();
        }
        main.execute(() -> {
            if (job.cancelled) {
                return;
            }
            if (problem == null && signed.fresh()) {
                cache.put(signed.ref(), signed.skin());
            }
            job.listener.done(problem != null ? new Outcome(null, null, problem)
                    : new Outcome(signed.ref(), signed.skin(), null));
            changed.accept(job.request.owner());
        });
    }

    private Signed sign(Job job) {
        Request request = job.request;
        byte[] png;
        switch (request.source()) {
            case Source.Player player -> {
                return borrow(player.name());
            }
            case Source.Url url -> png = backend.fetch(url.url());
            case Source.Image image -> png = image.png();
        }
        String variant = request.variant() != null ? request.variant() : SkinVariants.infer(png);
        SkinRef ref = SkinRef.of(png, variant);
        SkinProfile known = cache.find(ref);
        if (known != null) {
            return new Signed(ref, known, false);
        }
        int waited = 0;
        while (true) {
            try {
                MineSkinClient.Signed signed = backend.sign(png, variant, apiKey.get());
                return new Signed(ref, new SkinProfile(variant, signed.value(), signed.signature(), png), true);
            } catch (SkinError e) {
                int seconds = e.retryAfterSeconds() > 0 ? e.retryAfterSeconds() : DEFAULT_WAIT_SECONDS;
                if (!SkinError.RATE_LIMITED.equals(e.code()) || waited + seconds > MAX_WAIT_SECONDS) {
                    throw e;
                }
                waited += seconds;
                waitOut(job, seconds);
            }
        }
    }

    /** 被限流了:告诉等的人,然后等。 */
    private void waitOut(Job job, int seconds) {
        job.state = State.WAITING;
        synchronized (this) {
            announce(job.request.owner());
        }
        main.execute(() -> job.listener.waiting(seconds));
        try {
            sleeper.sleep(seconds * 1000L);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw SkinError.of(interrupted, SkinError.INTERRUPTED);
        }
        job.state = State.SIGNING;
    }

    /**
     * 借一个正版玩家的皮肤:Mojang 签好的,不经 MineSkin。缓存的键要原图的哈希,所以原图从签名数据里写的图床取;
     * 取不到、签名数据读不通,都是这一次借不成。
     */
    private Signed borrow(String playerName) {
        MojangSkinLookup.Result found = backend.lookup(playerName);
        if (found.problem() != null) {
            throw found.problem();
        }
        MojangSkins.Skin skin = found.skin();
        if (skin == null) {
            throw SkinError.of(SkinError.NO_SKIN);
        }
        SkinProfile.Textures textures = SkinProfile.texturesOf(skin.value());
        if (textures == null) {
            throw SkinError.of(SkinError.REPLY_BAD, "sessionserver.mojang.com");
        }
        byte[] png = backend.texture(textures.url());
        SkinRef ref = SkinRef.of(png, textures.variant());
        SkinProfile known = cache.find(ref);
        return known != null ? new Signed(ref, known, false)
                : new Signed(ref, new SkinProfile(textures.variant(), skin.value(), skin.signature(), png), true);
    }

    // ---- 通知 ----

    /** 队里每个人的排位都可能变了。 */
    private void announceAll() {
        queue.stream().map(job -> job.request.owner()).distinct().forEach(this::announce);
    }

    private void announce(UUID owner) {
        main.execute(() -> changed.accept(owner));
    }
}
