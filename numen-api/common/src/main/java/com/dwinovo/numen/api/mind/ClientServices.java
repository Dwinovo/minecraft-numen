package com.dwinovo.numen.api.mind;

import com.dwinovo.numen.api.Constants;
import com.dwinovo.numen.api.agent.skill.SkillRegistry;
import com.dwinovo.numen.api.mind.Bound;
import com.dwinovo.numen.api.mind.LlmMind;
import com.dwinovo.numen.api.home.Home;
import com.dwinovo.numen.api.mcp.server.ExternalHands;
import com.dwinovo.numen.api.mcp.server.McpMode;
import com.dwinovo.numen.api.mcp.client.McpClientConfig;
import com.dwinovo.numen.api.persona.PersonaLibrary;
import com.dwinovo.numen.api.platform.Services;
import com.dwinovo.numen.api.seat.SeatClient;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * 连着一台服务器期间她的大脑要用的、跟着这条连接走的东西:座位上的大脑、接进来的 MCP 工具的连接、外部大脑的端点、征询的去处。
 * 钥匙串、各库、偏好、型号目录属于这台电脑,在 {@link ClientMachine} 里,这里只用、不拥有。一个连接一份:连上时建,断线时 {@link #close}。
 *
 * <p>线程:大脑的线程({@code main})。
 */
public final class ClientServices {

    private final UUID player;
    private final ClientMachine machine;
    private final Executor main;
    private final ClientBrains brains;
    private final ClientMcp mcp;
    private final ConsentVenues venues;
    private final ClientMachine.Connection live;
    private McpMode external;

    /**
     * 游戏这一侧给大脑的东西。
     *
     * @param onMain  调用的线程是不是客户端主线程
     * @param roster  主人名下此刻在世界里的同伴
     * @param actions 召唤与遣散
     * @param answers 征询的答复怎么发回服务端
     */
    public record Game(BooleanSupplier onMain, Supplier<List<ExternalHands.Companion>> roster, LoopHands.Actions actions,
                       ConsentVenues.Answers answers) {}

    /**
     * @param player  这台电脑上的玩家
     * @param root    主人在这台电脑上的东西放哪({@code config/numen/client/})
     * @param seat    这条到服务器的座位连接
     * @param main    大脑的线程
     * @param game    游戏这一侧给的东西
     */
    public ClientServices(UUID player, ClientMachine machine, SeatClient seat, Executor main, Game game) {
        this.player = player;
        this.machine = machine;
        this.main = main;
        this.mcp = new ClientMcp(player, main, machine.library(McpClientConfig.KIND), machine.keychain(), () -> { });
        this.mcp.reconcile();
        this.venues = new ConsentVenues(game.answers());
        this.brains = new ClientBrains(seat, main, companion -> brain(seat, companion));
        LoopHands hands = new LoopHands(brains, main, game.onMain(), mcp, game.roster(), game.actions(), venues);
        this.external = new McpMode(new Home(machine.root().resolve("mcp"), machine.home().io()), player, hands, venues,
                Services.platform().modVersion(Constants.MOD_ID));
        this.external.start();
        this.live = new ClientMachine.Connection() {
            @Override
            public List<LoopHost> brains() {
                return List.copyOf(ClientServices.this.brains.all());
            }

            @Override
            public ClientMcp mcp() {
                return mcp;
            }

            @Override
            public McpMode external() {
                return external;
            }
        };
        machine.attach(live);
    }

    /** 一只同伴的大脑:家在她自己的目录里,模型是她绑的档案,人设正文每轮现取。 */
    private LoopHost brain(SeatClient seat, UUID companion) {
        PersonaLibrary personas = new PersonaLibrary(machine.library(PersonaLibrary.KIND));
        Home hers = new Home(machine.root().resolve("companions").resolve(companion.toString()), machine.home().io());
        return new LoopHost(new LoopHost.Parts(companion, player, hers, main, seat,
                new LlmMind(machine::providers, machine.models()), mcp, () -> external.driving(companion),
                machine.prefs()::initiative,
                (Bound bound) -> {
                    PersonaLibrary.Persona persona = personas.get(bound.id().toString());
                    return persona == null ? null : persona.text();
                },
                bundled -> SkillRegistry.of(machine.library(SkillRegistry.KIND), bundled),
                who -> who.equals(player) ? "" : who.toString(),
                Services.platform().modVersion(Constants.MOD_ID)));
    }

    /** 这条连接上的大脑。 */
    public ClientBrains brains() {
        return brains;
    }

    /** 接进来的 MCP 服务器。 */
    public ClientMcp mcp() {
        return mcp;
    }

    /** 外部大脑的端点:谁在驾驶、令牌、设置都在这里。 */
    public McpMode external() {
        return external;
    }

    /** 征询的去处:答复框之外的场所(外部大脑的客户端、渠道)。 */
    public ConsentVenues venues() {
        return venues;
    }

    /** 每个客户端刻一次:端点清扫过期的会话与令牌,各只的大脑往前走一步。 */
    public void tick() {
        external.tick();
        brains.tick();
    }

    /** 断线:大脑收掉,外部大脑的端点与 MCP 连接放手。 */
    public void close() {
        machine.detach(live);
        external.shutdown();
        venues.clearAll();
        brains.leave();
        mcp.shutdown();
    }
}
