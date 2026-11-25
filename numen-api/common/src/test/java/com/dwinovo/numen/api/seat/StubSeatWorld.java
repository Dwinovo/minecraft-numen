package com.dwinovo.numen.api.seat;

import com.dwinovo.numen.api.agent.loop.Program;
import com.dwinovo.numen.api.program.ProgramRunner;
import com.dwinovo.numen.api.script.Modules;
import com.google.gson.JsonObject;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/** 单测用的服务端世界:主线程就是调用方,程序停在测试手里,创建者与目录版本由测试定。 */
final class StubSeatWorld implements SeatWorld {

    /** 谁创建了谁:不在表里的同伴不存在。 */
    final Map<UUID, UUID> creators = new ConcurrentHashMap<>();
    final AtomicReference<String> catalogVersion = new AtomicReference<>("v1");
    volatile String state = "<runtime_state>idle</runtime_state>";
    volatile BodyTask task;
    volatile long gameTime = 100;
    final List<String> said = new CopyOnWriteArrayList<>();
    /** 她说话时带的是谁、在哪儿说的。 */
    final List<com.dwinovo.numen.api.brain.Spoken> spokenTo = new CopyOnWriteArrayList<>();
    /** 创建者之外、在她主人表上的人。 */
    final Map<UUID, java.util.Set<UUID>> sharedWith = new ConcurrentHashMap<>();
    final Programs programs = new Programs();

    /** 经座位起的程序:停在这里,测试决定什么时候给结局。 */
    static final class Programs implements ProgramRunner {
        final Map<String, Consumer<Program.Outcome>> running = new ConcurrentHashMap<>();
        final Map<String, Modules> modules = new ConcurrentHashMap<>();
        /** 每段程序替哪一位 keeper 做事。 */
        final Map<String, UUID> keepers = new ConcurrentHashMap<>();
        final List<String> runs = new CopyOnWriteArrayList<>();
        final List<String> interrupts = new CopyOnWriteArrayList<>();
        final List<String> cuts = new CopyOnWriteArrayList<>();
        final List<UUID> bodyStops = new CopyOnWriteArrayList<>();
        /** 被切断时给的结局;不设就只记下、程序照跑。 */
        volatile Program.Outcome onCut;

        @Override
        public void run(UUID companion, String programId, String code, Modules with, UUID keeper,
                        Consumer<Program.Outcome> done) {
            runs.add(programId + ": " + code);
            keepers.put(programId, keeper);
            modules.put(programId, with);
            running.put(programId, done);
        }

        /** 这段程序起跑时能用的模块。 */
        Modules modulesOf(String programId) {
            return modules.get(programId);
        }

        @Override
        public void interrupt(UUID companion, String programId, String why) {
            interrupts.add(programId + ": " + why);
        }

        @Override
        public void cutOff(UUID companion, String programId, boolean stopBody) {
            cuts.add(programId + ": " + stopBody);
            if (onCut != null) {
                Consumer<Program.Outcome> done = running.remove(programId);
                if (done != null) {
                    done.accept(onCut);
                }
            }
        }

        @Override
        public void stopBody(UUID companion) {
            bodyStops.add(companion);
        }

        /** 程序跑完了。 */
        void finish(String programId, Program.Outcome outcome) {
            running.remove(programId).accept(outcome);
        }
    }

    StubSeatWorld createdBy(UUID companion, UUID player) {
        creators.put(companion, player);
        return this;
    }

    @Override
    public Executor main() {
        return Runnable::run;
    }

    @Override
    public boolean mayOpen(UUID player, UUID companion) {
        return player.equals(creators.get(companion));
    }

    @Override
    public SeatState state(UUID companion) {
        return new SeatState(state, task, gameTime, 6000);
    }

    @Override
    public long gameTime() {
        return gameTime;
    }

    @Override
    public long dayTime() {
        return 6000;
    }

    @Override
    public ProgramRunner programs() {
        return programs;
    }

    @Override
    public boolean keeps(UUID companion, UUID person) {
        return person.equals(creators.get(companion)) || sharedWith.getOrDefault(companion, java.util.Set.of()).contains(person);
    }

    @Override
    public void say(UUID companion, String text, com.dwinovo.numen.api.brain.Spoken spoken) {
        said.add(text);
        spokenTo.add(spoken);
    }

    @Override
    public JsonObject catalog(UUID companion) {
        JsonObject catalog = new JsonObject();
        catalog.addProperty("version", catalogVersion.get());
        JsonObject api = new JsonObject();
        for (String key : java.util.List.of("groups", "functions", "shared", "modules")) {
            api.add(key, new com.google.gson.JsonArray());
        }
        api.add("classes", new JsonObject());
        catalog.add("api", api);
        catalog.add("skills", new com.google.gson.JsonArray());
        catalog.add("instincts", new com.google.gson.JsonArray());
        return catalog;
    }
}
