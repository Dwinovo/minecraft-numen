package com.dwinovo.numen.tools.station;

import com.dwinovo.numen.api.NumenApi;
import com.dwinovo.numen.api.sdk.Call;
import com.dwinovo.numen.api.sdk.Doc;
import com.dwinovo.numen.api.sdk.Example;
import com.dwinovo.numen.api.sdk.Fn;
import com.dwinovo.numen.api.sdk.Note;
import com.dwinovo.numen.api.sdk.Pending;
import com.dwinovo.numen.api.sdk.SeeAlso;
import com.dwinovo.numen.api.sdk.ServerCall;
import com.dwinovo.numen.script.ApiError;
import com.dwinovo.numen.script.ErrorKind;
import com.dwinovo.numen.task.station.StationTaskRecord.Deed;
import com.dwinovo.numen.task.station.StationTaskRecord.Move;
import com.dwinovo.numen.tools.Books;
import net.minecraft.core.BlockPos;
import net.minecraft.world.inventory.LecternMenu;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * {@code numen.lectern}:讲台上的书。读它(标题、作者、每一页的字、翻到第几页),翻到某一页(按钮 100 起是跳页),把书取下来。
 * 取书是从讲台里拿东西,和从别的容器里拿一样是搬东西的一步,先过权限层;书取下之后原版自己把讲台的界面合上。
 */
public final class LecternApi {

    private static final String KIND = "lectern";
    private static final int[] BOOK = {0};

    private LecternApi() {}

    public static void install(NumenApi numen) {
        numen.api("lectern", "A lectern with a book on it: read the book, turn it to a page, take it off.",
                LecternApi.class);
    }

    /** 讲台上的书和它翻到的那一页。 */
    @Doc("The book on a lectern.")
    public record OnLectern(@Doc("The book: its title, author and the text of every page.") Books.Book book,
                            @Doc("The page it is open at, counted from 1.") int page) {}

    /** 哪座讲台。 */
    public record Lectern(@Doc("The lectern's cell.") BlockPos lectern) {}

    @Fn("Read the book on a lectern: its title, author, the text of every page and the page it is open at.")
    @Example("local r = numen.lectern.read({x = 120, y = 64, z = -35}) print(r.book.title, r.book.pages[r.page])")
    @Note("Walks up to the lectern when it is out of reach and reads the book where it lies; nothing is taken.")
    @SeeAlso({"numen.lectern.page", "numen.lectern.take", "numen.inv.read"})
    public static Pending<OnLectern> read(ServerCall call, Lectern args) {
        AtomicReference<OnLectern> read = new AtomicReference<>();
        return Stations.at(call, args.lectern(), LecternMenu.class, KIND, List.of(new Deed((menu, her) -> {
            read.set(read((LecternMenu) menu));
            return "read the book, " + read.get().book().pages().size() + " pages, open at page " + read.get().page();
        })), read::get);
    }

    /** 哪座讲台、第几页。 */
    public record Page(@Doc("The lectern's cell.") BlockPos lectern,
                       @Doc("The page to turn to, counted from 1.") int page) {}

    @Fn("Turn the book on a lectern to a page.")
    @Example("numen.lectern.page({x = 120, y = 64, z = -35}, 3)")
    @Note("Walks up to the lectern when it is out of reach and turns the book; it stays on that page for whoever "
            + "reads it next. Returns the page it is open at now. A page the book does not have fails, saying how "
            + "many it has.")
    @SeeAlso("numen.lectern.read")
    public static Pending<Integer> page(ServerCall call, Page args) {
        int n = args.page();
        if (n < 1) {
            throw new ApiError(ErrorKind.BAD_ARGUMENT, "pages are counted from 1; " + n + " is not one",
                    Call.of("numen.lectern.page", args.lectern(), 1));
        }
        return Stations.at(call, args.lectern(), LecternMenu.class, KIND, List.of(new Deed((menu, her) -> {
            LecternMenu lectern = (LecternMenu) menu;
            int pages = read(lectern).book().pages().size();
            if (n > pages) {
                throw new ApiError(ErrorKind.NOT_FOUND, "the book has " + pages + " pages; page " + n
                        + " does not exist", null);
            }
            her.packets().menuButton(LecternMenu.BUTTON_PAGE_JUMP_RANGE_START + n - 1);
            if (lectern.getPage() != n - 1) {
                throw new ApiError(ErrorKind.FAILED, "the lectern stayed on page " + (lectern.getPage() + 1), null);
            }
            return "turned it to page " + n + " of " + pages;
        })), () -> n);
    }

    @Fn("Take the book off a lectern into your inventory.")
    @Example("local book = numen.lectern.take({x = 120, y = 64, z = -35}) print(book.title)")
    @Note("Walks up to the lectern when it is out of reach and takes the book. Returns the book you took, as "
            + "numen.inv.read reads it.")
    @Note("Taking a book off someone's lectern may ask your owner first; the call waits for the answer.")
    @SeeAlso({"numen.lectern.read", "numen.inv.read"})
    public static Pending<Books.Book> take(ServerCall call, Lectern args) {
        AtomicReference<OnLectern> read = new AtomicReference<>();
        return Stations.at(call, args.lectern(), LecternMenu.class, KIND, List.of(
                new Deed((menu, her) -> {
                    read.set(read((LecternMenu) menu));
                    return null;
                }),
                Move.take(null, null, BOOK, null, false)), () -> read.get().book());
    }

    /** 讲台上那本书;讲台上放的不是书(原版只让放书)就按没有书收场。 */
    private static OnLectern read(LecternMenu menu) {
        Books.Book book = Books.read(menu.getBook()).orElseThrow(() -> new ApiError(ErrorKind.NOT_FOUND,
                "the lectern holds no book", null));
        return new OnLectern(book, menu.getPage() + 1);
    }
}
