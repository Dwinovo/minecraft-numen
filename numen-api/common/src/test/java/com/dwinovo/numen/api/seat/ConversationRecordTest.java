package com.dwinovo.numen.api.seat;

import com.dwinovo.numen.api.agent.llm.ConvoState;
import com.dwinovo.numen.api.agent.llm.Rows;
import com.dwinovo.numen.api.agent.provider.AssistantTurn;
import com.dwinovo.numen.mcp.rpc.RpcException;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 对话记录在服务端:脑子经座位读它、往后写它;她的主人表上的人能翻页、订阅;群里各成员的行按会话印归并;
 * 清空是换一个新的会话,旧的留着。客户端与服务端经进程内的管道说话,消息照线上的样子过一遍。
 */
@Tag("mc")
class ConversationRecordTest {

    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID FRIEND = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final UUID STRANGER = UUID.fromString("00000000-0000-0000-0000-00000000000c");
    private static final UUID HER = UUID.fromString("00000000-0000-0000-0000-0000000000c1");
    private static final UUID OTHER = UUID.fromString("00000000-0000-0000-0000-0000000000c2");

    private static final Executor THREADS = Executors.newVirtualThreadPerTaskExecutor();

    private StubSeatWorld world;
    private Seats seats;
    private SeatClient owner;

    @BeforeEach
    void wire() {
        world = new StubSeatWorld().createdBy(HER, OWNER).createdBy(OTHER, OWNER);
        world.sharedWith.put(HER, java.util.Set.of(FRIEND));
        seats = new Seats(world, THREADS);
        owner = SeatPipe.connect(seats, OWNER, Runnable::run, THREADS).client();
    }

    @AfterEach
    void unplug() {
        seats.clear();
    }

    private static <T> T get(CompletableFuture<T> future) throws Exception {
        return future.get(10, TimeUnit.SECONDS);
    }

    private static int refusedWith(CompletableFuture<?> future) throws Exception {
        ExecutionException failed = assertThrows(ExecutionException.class, () -> get(future));
        return assertInstanceOf(RpcException.class, failed.getCause()).error().code();
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + 10_000_000_000L;
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("it did not happen in ten seconds");
            }
            Thread.sleep(2);
        }
    }

    /** 一个写手写下几条消息得到的行。 */
    private static List<JsonObject> written(Rows.Writer writer, Rows.Stamp stamp, ConvoState.Msg... messages) {
        List<JsonObject> rows = new ArrayList<>();
        for (ConvoState.Msg msg : messages) {
            rows.addAll(writer.write(msg, stamp));
        }
        return rows;
    }

    private static ConvoState.Msg said(String text) {
        return new ConvoState.Msg.User("<query>" + text + "</query>");
    }

    private static ConvoState.Msg replied(String text) {
        return new ConvoState.Msg.Assistant(new AssistantTurn(text, List.of(), null));
    }

    private List<ConvoState.Msg> record(SeatClient reader, UUID companion) throws Exception {
        return get(reader.page(companion, null, "", History.PAGE)).entries().stream()
                .map(com.dwinovo.numen.api.agent.conversation.Transcript.Entry::msg).toList();
    }

    @Test
    void whatTheSeatHolderAppendsIsOnTheServerInOrderAndAFreshOpenReadsItBack() throws Exception {
        get(owner.open(HER, 0));
        ConversationOpened first = get(owner.openConversation(HER));
        Rows.Writer writer = new Rows.Writer("t");
        owner.appendRows(HER, first.conversation(), written(writer, Rows.Stamp.NONE, said("一"), replied("好"), said("二")));
        owner.appendRows(HER, first.conversation(), written(writer, Rows.Stamp.NONE, replied("嗯")));

        ConversationOpened again = get(owner.openConversation(HER));

        assertEquals(first.conversation(), again.conversation());
        assertEquals(List.of(said("一"), replied("好"), said("二"), replied("嗯")),
                Rows.replay(again.opening().rows(), again.opening().last()).context());
        assertEquals(List.of(said("一"), replied("好"), said("二"), replied("嗯")), record(owner, HER));
    }

    @Test
    void onlyTheSeatHolderMayAppendAndOnlyToTheConversationSheIsWriting() throws Exception {
        get(owner.open(HER, 0));
        ConversationOpened opened = get(owner.openConversation(HER));
        SeatClient friend = SeatPipe.connect(seats, FRIEND, Runnable::run, THREADS).client();
        Rows.Writer writer = new Rows.Writer("t");

        friend.appendRows(HER, opened.conversation(), written(writer, Rows.Stamp.NONE, said("我也想写")));
        owner.appendRows(HER, UUID.randomUUID(), written(writer, Rows.Stamp.NONE, said("写错了地方")));
        owner.appendRows(HER, opened.conversation(), written(writer, Rows.Stamp.NONE, said("这条才算")));

        assertEquals(List.of(said("这条才算")), record(owner, HER), "the others were dropped, not written");
    }

    @Test
    void herKeepersCanPageAndWatchWithoutHoldingTheSeatAndStrangersCannot() throws Exception {
        get(owner.open(HER, 0));
        ConversationOpened opened = get(owner.openConversation(HER));
        owner.appendRows(HER, opened.conversation(), written(new Rows.Writer("t"), Rows.Stamp.NONE, said("一")));
        SeatClient friend = SeatPipe.connect(seats, FRIEND, Runnable::run, THREADS).client();
        SeatClient stranger = SeatPipe.connect(seats, STRANGER, Runnable::run, THREADS).client();

        assertEquals(List.of(said("一")), record(friend, HER), "a keeper she was shared with reads her record");
        assertEquals(-32602, refusedWith(stranger.page(HER, null, "", History.PAGE)));
        assertEquals(-32602, refusedWith(stranger.watch(HER)));
        assertEquals(SeatErrors.NOT_YOURS, refusedWith(friend.open(HER, 0)), "reading is not sitting");
    }

    @Test
    void aWatcherIsPushedTheRowsThatLandAndTheNewConversationAfterAClear() throws Exception {
        get(owner.open(HER, 0));
        ConversationOpened opened = get(owner.openConversation(HER));
        SeatClient friend = SeatPipe.connect(seats, FRIEND, Runnable::run, THREADS).client();
        List<UUID> conversations = new CopyOnWriteArrayList<>();
        List<Integer> sizes = new CopyOnWriteArrayList<>();
        friend.onRows((companion, conversation, rows) -> {
            conversations.add(conversation);
            sizes.add(rows.size());
        });
        get(friend.watch(HER));

        Rows.Writer writer = new Rows.Writer("t");
        owner.appendRows(HER, opened.conversation(), written(writer, Rows.Stamp.NONE, said("一")));
        await(() -> sizes.size() == 1);
        UUID next = UUID.randomUUID();
        owner.newConversation(HER, next);
        await(() -> sizes.size() == 2);

        assertEquals(List.of(opened.conversation(), next), conversations);
        assertEquals(List.of(1, 0), sizes, "a new conversation is announced as a push with no rows");
        assertEquals(next, world.conversations.get(HER), "the registry holds her current conversation");
    }

    @Test
    void clearingStartsANewConversationAndTheOldFileIsKept() throws Exception {
        get(owner.open(HER, 0));
        ConversationOpened old = get(owner.openConversation(HER));
        Rows.Writer writer = new Rows.Writer("t");
        owner.appendRows(HER, old.conversation(), written(writer, Rows.Stamp.NONE, said("以前")));
        UUID next = UUID.randomUUID();
        owner.newConversation(HER, next);
        writer.restart();
        owner.appendRows(HER, next, written(writer, Rows.Stamp.NONE, said("以后")));

        assertEquals(List.of(said("以后")), record(owner, HER), "the panel and the brain read the new conversation");
        ConversationOpened reopened = get(owner.openConversation(HER));
        assertEquals(next, reopened.conversation());
        assertEquals(List.of(said("以前")), world.transcripts().read(old.conversation(),
                rows -> Rows.lines(rows).stream().map(Rows.Line::msg).toList()).get(), "the old record is untouched");
    }

    @Test
    void aGroupIsReadAsTheMergeOfItsMembersRowsMarkedWithItsTag() throws Exception {
        world.groups.put("G", List.of(HER, OTHER));
        get(owner.open(HER, 0));
        get(owner.open(OTHER, 0));
        ConversationOpened hers = get(owner.openConversation(HER));
        ConversationOpened others = get(owner.openConversation(OTHER));
        Rows.Stamp group = new Rows.Stamp("G", null);
        owner.appendRows(HER, hers.conversation(), written(new Rows.Writer("t"), group, replied("我在"),
                replied("她也在")));
        owner.appendRows(OTHER, others.conversation(), written(new Rows.Writer("t"), group, replied("我也在")));
        owner.appendRows(HER, hers.conversation(), written(new Rows.Writer("t"), Rows.Stamp.NONE, replied("私下说的")));

        History.Page page = get(owner.page(null, "G", "", History.PAGE));

        assertEquals(java.util.Set.of("我在", "她也在", "我也在"), page.entries().stream()
                .map(e -> ((ConvoState.Msg.Assistant) e.msg()).turn().content()).collect(java.util.stream.Collectors.toSet()),
                "only the rows marked with the tag, from both members");
        assertEquals(3, page.entries().size());
    }

    @Test
    void pagingWalksBackwardsByTheCursorAndAddsUpToTheWholeRecord() throws Exception {
        get(owner.open(HER, 0));
        ConversationOpened opened = get(owner.openConversation(HER));
        Rows.Writer writer = new Rows.Writer("t");
        for (int i = 0; i < 130; i++) {
            owner.appendRows(HER, opened.conversation(), written(writer, Rows.Stamp.NONE, said("第" + i + "句")));
        }
        get(owner.openConversation(HER));   // 排在追加之后:都落了盘

        List<ConvoState.Msg> whole = new ArrayList<>();
        String cursor = "";
        History.Page page;
        int pages = 0;
        do {
            page = get(owner.page(HER, null, cursor, History.PAGE));
            whole.addAll(0, page.entries().stream().map(e -> e.msg()).toList());
            cursor = page.cursor();
            pages++;
        } while (page.more());

        assertEquals(3, pages, "130 rows, 60 a page");
        assertEquals(130, whole.size());
        assertEquals(said("第0句"), whole.getFirst());
        assertEquals(said("第129句"), whole.getLast());
    }
}
