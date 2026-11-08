package com.dwinovo.numen.api.mcp.server;

/**
 * 「复制接入提示词」按钮的内容——把"哪个客户端该写哪种配置文件"这摊活外包给
 * 用户自己的 AI:用户复制这段话发给 Claude/任何助手,对方照着把 MCP 配好。
 *
 * <p>用英文写:收这段话的可能是任何模型、任何客户端,英文的指令遵循度最稳;
 * 末尾单独叮嘱它用用户的语言回话,所以用户侧体验仍是母语。
 *
 * <p>端点内嵌进文本;令牌不在里面——令牌是主人在游戏里用 {@code /numen token create} 另发的,
 * 提示词里只写头怎么带,所以这段话本身可以放心发给任何 AI。
 */
public final class McpAccessPrompt {

    private McpAccessPrompt() {}

    /** 为当前端点生成整段提示词。 */
    public static String build(String endpoint) {
        return """
                I'm playing Minecraft with a mod called Numen, and I want you to connect to it.

                ## What Numen is

                Numen puts AI-controlled, player-like companions into my live Minecraft world. It exposes \
                an MCP (Model Context Protocol) server on my machine, so an external AI — you — can take \
                over a companion's body and play the game as it: perceive the world, move, mine, build, \
                craft, fight, use containers.

                ## Your task

                Set up the MCP connection on this machine, then confirm it works. Here are the details:

                - MCP endpoint: %s
                - Auth: every request must carry the header `Authorization: Bearer <token>`. The token is \
                issued in the game by the owner with `/numen token create brain` (the game prints it once); \
                ask me to paste it, and put it where `<token>` is below. Never write it into a shared file.

                ### Step 1 — figure out which client you're running in

                You might be Claude Desktop, Claude Code, Cursor, Cline, Windsurf, or something else. \
                Work out which one, and where its MCP config lives (for example \
                `claude_desktop_config.json` for Claude Desktop, `.mcp.json` or the `claude mcp add` \
                command for Claude Code, `~/.cursor/mcp.json` for Cursor). If you can't tell, ask me.

                ### Step 2 — add the server

                Numen speaks MCP over plain HTTP. If your client can dial an HTTP/streamable-HTTP MCP \
                server directly, point it straight at the endpoint above. If it only supports stdio \
                servers (Claude Desktop today), bridge it with `mcp-remote`:

                ```json
                {
                  "mcpServers": {
                    "numen": {
                      "command": "npx",
                      "args": ["-y", "mcp-remote", "%s", "--header", "Authorization: Bearer <token>"]
                    }
                  }
                }
                ```

                Write that into the right config file for your client (merge into any existing \
                `mcpServers` block rather than overwriting it), then tell me if I need to restart the \
                client for it to pick the server up. `npx` needs Node.js — if it's missing, tell me how \
                to install it.

                ### Step 3 — verify

                Once connected, call `list_companions`. If no companion is live, call `create_companion` \
                with a name (3–16 letters, digits, or underscore) and then `list_companions` again to \
                confirm it arrived.

                ## How to drive a companion once you're in

                The server sends its own instructions when you connect (how the tools work, how events and the                 companion's state reach you, and the guide the companion's built-in brain reads); follow those.                 Every tool takes a `companion` argument (name or id), and there is no take-control step.

                One more thing: talk to me in the language I'm writing to you in, even though these \
                instructions are in English.""".formatted(endpoint, endpoint);
    }
}
