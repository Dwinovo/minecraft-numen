package com.dwinovo.numen.e2e;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * 服务端的控制台口(Source RCON 协议):场景用它布置世界和玩家(给东西、定权限),这些是管理员的事,不是玩家能做的。
 * 玩家能做的事一律经 {@link Bot} 走游戏协议。
 */
final class Rcon implements AutoCloseable {

    private static final int LOGIN = 3;
    private static final int COMMAND = 2;

    private final Socket socket;
    private final DataInputStream in;
    private final DataOutputStream out;
    private int nextId = 1;

    Rcon(String host, int port, String password) throws IOException {
        socket = new Socket(host, port);
        socket.setSoTimeout(15_000);
        in = new DataInputStream(socket.getInputStream());
        out = new DataOutputStream(socket.getOutputStream());
        int id = write(LOGIN, password);
        if (read().id() != id) {
            throw new IOException("RCON 密码不对");
        }
    }

    /** 在控制台敲一条指令(不带斜杠),返回服务端的回话。 */
    synchronized String command(String command) throws IOException {
        int id = write(COMMAND, command);
        Packet reply = read();
        if (reply.id() != id) {
            throw new IOException("RCON 回包对不上请求: " + reply.id() + " != " + id);
        }
        return reply.body();
    }

    private int write(int type, String body) throws IOException {
        int id = nextId++;
        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        ByteBuffer packet = ByteBuffer.allocate(14 + payload.length).order(ByteOrder.LITTLE_ENDIAN);
        packet.putInt(10 + payload.length).putInt(id).putInt(type).put(payload).put((byte) 0).put((byte) 0);
        out.write(packet.array());
        out.flush();
        return id;
    }

    private Packet read() throws IOException {
        byte[] head = new byte[4];
        in.readFully(head);
        int length = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN).getInt();
        byte[] rest = new byte[length];
        in.readFully(rest);
        ByteBuffer buffer = ByteBuffer.wrap(rest).order(ByteOrder.LITTLE_ENDIAN);
        int id = buffer.getInt();
        buffer.getInt();
        byte[] body = new byte[length - 10];
        buffer.get(body);
        return new Packet(id, new String(body, StandardCharsets.UTF_8));
    }

    @Override
    public void close() throws IOException {
        socket.close();
    }

    private record Packet(int id, String body) {}
}
