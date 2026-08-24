package com.dwinovo.numen.client.stt;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/** 极简 multipart/form-data 构造:一个文本字段 {@code model} + 一个文件字段 {@code file}。 */
final class Multipart {

    private Multipart() {}

    static byte[] build(String boundary, String model, String filename, byte[] fileBytes) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        String dash = "--" + boundary + "\r\n";
        StringBuilder head = new StringBuilder();
        head.append(dash)
                .append("Content-Disposition: form-data; name=\"model\"\r\n\r\n")
                .append(model == null ? "" : model).append("\r\n")
                .append(dash)
                .append("Content-Disposition: form-data; name=\"file\"; filename=\"").append(filename).append("\"\r\n")
                .append("Content-Type: audio/wav\r\n\r\n");
        String tail = "\r\n--" + boundary + "--\r\n";
        try {
            out.write(head.toString().getBytes(StandardCharsets.UTF_8));
            out.write(fileBytes);
            out.write(tail.getBytes(StandardCharsets.UTF_8));
        } catch (java.io.IOException e) {
            throw new RuntimeException(e); // ByteArrayOutputStream never throws
        }
        return out.toByteArray();
    }
}
