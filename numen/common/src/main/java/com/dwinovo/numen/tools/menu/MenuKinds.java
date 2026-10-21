package com.dwinovo.numen.tools.menu;

import com.dwinovo.numen.act.MenuOrigin;
import com.dwinovo.numen.api.entity.NumenPlayer;
import com.dwinovo.numen.api.permission.Action;
import com.dwinovo.numen.api.sdk.Call;
import com.dwinovo.numen.api.sdk.Pending;
import com.dwinovo.numen.api.sdk.ServerCall;
import com.dwinovo.numen.script.ApiError;
import com.dwinovo.numen.script.ErrorKind;
import com.dwinovo.numen.tools.GuiOps;
import net.minecraft.core.BlockPos;
import net.minecraft.world.inventory.AbstractContainerMenu;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * 有名堂的界面都在这里:{@link #KINDS} 是 Numen 认识的几种({@link MenuKind}),开着的界面是哪一种由它的菜单类定。
 * {@code numen.gui.view} 的 {@code screen} 读它({@link #read}),{@code numen.gui.choose}、{@code rename}、{@code page}、{@code toggle}
 * 按它找到那一种、问它有没有这件事({@link Bound}),{@code numen.gui.take} 拿容器里的东西前问它自己的拿法({@link #takeOut})。
 * 加一种界面是写一个 {@link MenuKind} 再列在这里,别处不改。
 */
public final class MenuKinds {

    private static final List<MenuKind<?>> KINDS = List.of(
            new EnchantingKind(), new StonecutterKind(), new LoomKind(), new TraderKind(), new BeaconKind(),
            new AnvilKind(), new LecternKind(), new CrafterKind());

    private MenuKinds() {}

    /** 开着的界面按它的种类说的话;不是有名堂的界面(箱子、熔炉、她自己的背包)是空。 */
    public static Optional<Screen> read(NumenPlayer her) {
        return bound(her).map(bound -> bound.read(her));
    }

    /** 开着的界面归界面自己拿的那一格:已经交给原版是 true;不归它管、或没有这样的界面是 false。 */
    public static boolean takeOut(NumenPlayer her, int slot) {
        return bound(her).map(bound -> bound.takeOut(her, slot)).orElse(false);
    }

    public static Pending<GuiOps.Window> choose(ServerCall call, Pick first, Pick second) {
        return open(call.her(), "choose").choose(call, first, second);
    }

    public static Pending<GuiOps.Window> rename(ServerCall call, String name) {
        return open(call.her(), "rename").rename(call, name);
    }

    public static Pending<GuiOps.Window> page(ServerCall call, int page) {
        return open(call.her(), "page").page(call, page);
    }

    public static Pending<GuiOps.Window> toggle(ServerCall call, int slot) {
        return open(call.her(), "toggle").toggle(call, slot);
    }

    /** 开着的界面和它的种类;没有对应的种类是空。 */
    private static Optional<Bound<?>> bound(NumenPlayer her) {
        AbstractContainerMenu open = her.containerMenu;
        for (MenuKind<?> kind : KINDS) {
            if (kind.menu().isInstance(open)) {
                return Optional.<Bound<?>>of(bind(kind, open));
            }
        }
        return Optional.empty();
    }

    private static <M extends AbstractContainerMenu> Bound<M> bind(MenuKind<M> kind, AbstractContainerMenu open) {
        return new Bound<>(kind, kind.menu().cast(open));
    }

    /** 开着的有名堂的界面,做事前要有:没有就当场说这个界面能做什么。 */
    private static Bound<?> open(NumenPlayer her, String verb) {
        return bound(her).orElseThrow(() -> new ApiError(ErrorKind.BAD_ARGUMENT, "this window (" + name(her)
                + ") takes no numen.gui." + verb + ": " + offers(verb) + "; its items move with numen.gui.put, take, "
                + "move and quick", Call.of("numen.gui.view")));
    }

    private static String name(NumenPlayer her) {
        return her.containerMenu == her.inventoryMenu ? "your own inventory" : her.containerMenu.getClass().getSimpleName();
    }

    /** 哪些界面有这件事,从各种类实现了哪个小接口读出来。 */
    private static String offers(String verb) {
        List<String> names = new ArrayList<>();
        for (MenuKind<?> kind : KINDS) {
            if (can(kind, verb)) {
                names.add(kind.name());
            }
        }
        return "it works in " + String.join(", ", names);
    }

    private static boolean can(MenuKind<?> kind, String verb) {
        return switch (verb) {
            case "choose" -> kind instanceof MenuKind.Chooses<?>;
            case "rename" -> kind instanceof MenuKind.Renames<?>;
            case "page" -> kind instanceof MenuKind.Pages<?>;
            case "toggle" -> kind instanceof MenuKind.Toggles<?>;
            default -> throw new IllegalArgumentException(verb);
        };
    }

    /**
     * 开着的有名堂的界面,连同它的种类。做一件事是同一套步骤:过权限层(这种界面会动到世界里的东西时)、等回来再认一次界面还是它、记下
     * 前后的样子、让种类去发包读回来、把变了的事如实报告并交回新的界面。
     */
    private static final class Bound<M extends AbstractContainerMenu> {

        private final MenuKind<M> kind;
        private final M menu;

        Bound(MenuKind<M> kind, M menu) {
            this.kind = kind;
            this.menu = menu;
        }

        Screen read(NumenPlayer her) {
            return kind.read(menu, her);
        }

        boolean takeOut(NumenPlayer her, int slot) {
            return kind instanceof MenuKind.Takes<M> takes && takes.takeOut(menu, her, slot);
        }

        Pending<GuiOps.Window> choose(ServerCall call, Pick first, Pick second) {
            if (!(kind instanceof MenuKind.Chooses<M> chooses)) {
                throw cannot("choose");
            }
            return act(call, () -> chooses.choose(menu, call.her(), first, second));
        }

        Pending<GuiOps.Window> rename(ServerCall call, String name) {
            if (!(kind instanceof MenuKind.Renames<M> renames)) {
                throw cannot("rename");
            }
            return act(call, () -> renames.rename(menu, call.her(), name));
        }

        Pending<GuiOps.Window> page(ServerCall call, int page) {
            if (!(kind instanceof MenuKind.Pages<M> pages)) {
                throw cannot("page");
            }
            return act(call, () -> pages.page(menu, call.her(), page));
        }

        Pending<GuiOps.Window> toggle(ServerCall call, int slot) {
            if (!(kind instanceof MenuKind.Toggles<M> toggles)) {
                throw cannot("toggle");
            }
            return act(call, () -> toggles.toggle(menu, call.her(), slot));
        }

        private ApiError cannot(String verb) {
            return new ApiError(ErrorKind.BAD_ARGUMENT, "this window (" + kind.name() + ") takes no numen.gui." + verb
                    + ": " + offers(verb), Call.of("numen.gui.view"));
        }

        private Pending<GuiOps.Window> act(ServerCall call, Supplier<String> deed) {
            NumenPlayer her = call.her();
            Action gate = kind.gate(menu, her);
            Pending<Void> allowed = gate == null ? Pending.of(null) : call.authorize(gate);
            return allowed.thenWait(ok -> {
                // 等主人答复的时候界面可能已经合上了:不对着别的界面把这件事做下去
                if (her.containerMenu != menu) {
                    throw new ApiError(ErrorKind.FAILED, "the " + kind.name() + " window closed while waiting",
                            Call.of("numen.gui.view"));
                }
                Receipt before = Receipt.open(her);
                String did = deed.get();
                List<String> changes = before.changes(her);
                Pending<GuiOps.Window> done = Pending.create();
                done.report(changes.isEmpty() ? did + "." : did + ": " + String.join("; ", changes) + ".");
                done.complete(GuiOps.window(her));
                return done;
            });
        }
    }

    /**
     * 开着的界面是右键哪一格打开的、会动到它(讲台翻页、信标定效果、合成器开关):权限层要裁决的"用这一格"。界面不是右键方块打开的
     * (来历不明)就没有格子可问,是 null。
     */
    static Action useOpenedBlock(NumenPlayer her) {
        BlockPos block = MenuOrigin.block(her);
        return block == null ? null : Action.useBlock(block, her.level().getBlockState(block));
    }
}
