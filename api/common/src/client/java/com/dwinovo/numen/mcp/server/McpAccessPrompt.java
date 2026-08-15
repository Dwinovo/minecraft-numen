package com.dwinovo.numen.mcp.server;

/**
 * 「复制接入提示词」按钮的内容——把"哪个客户端该写哪种配置文件"这摊活外包给
 * 用户自己的 AI:用户复制这段话发给 Claude/任何助手,对方照着把本机 MCP 配好。
 *
 * <p>用英文写:收这段话的可能是任何模型、任何客户端,英文的指令遵循度最稳;
 * 末尾单独叮嘱它用用户的语言回话,所以用户侧体验仍是母语。
 *
 * <p>端点与令牌直接内嵌进文本,用户零填空——这也是为什么按钮旁必须提示
 * "提示词含你的本机令牌,只发给你信任的 AI"。
 */
final class McpAccessPrompt {

    private McpAccessPrompt() {}

    /** 为当前端点/令牌生成整段提示词。 */
    static String build(String endpoint, String token) {
        String auth = token == null || token.isBlank() ? "" : token;
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
                %s
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
                      "args": [%s]
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

                - Every tool takes a `companion` argument (name or id), so each call targets one body. \
                There is no take-control handshake — just call tools.
                - Action tools (`goto`, `mine`, `build`, `fish`, …) are BACKGROUND tasks: they \
                return a task id immediately. Poll `task_status` until the body goes idle — as an \
                external driver you do NOT receive `task_finished` events, so polling is the only way \
                to know it's done. `task_stop` cancels.
                - One body runs one task at a time. If you get a "body is busy" refusal, either wait for \
                that task or `task_stop` it.
                - You're blind between calls: perceive with `get_self_status` / `scan_blocks` / \
                `scan_nearby_entities` before and after acting.
                - It's survival mode — the tools do only what a real player can. No give, no setblock.

                One more thing: talk to me in the language I'm writing to you in, even though these \
                instructions are in English.""".formatted(
                        endpoint,
                        auth.isBlank()
                                ? "- No auth token is set — the endpoint is loopback-only, so no "
                                        + "Authorization header is needed.\n"
                                : "- Auth token: `" + auth + "` — every request must carry the header "
                                        + "`Authorization: Bearer " + auth + "`.\n",
                        auth.isBlank()
                                ? "\"-y\", \"mcp-remote\", \"" + endpoint + "\""
                                : "\"-y\", \"mcp-remote\", \"" + endpoint + "\", \"--header\", "
                                        + "\"Authorization: Bearer " + auth + "\"");
    }
}
