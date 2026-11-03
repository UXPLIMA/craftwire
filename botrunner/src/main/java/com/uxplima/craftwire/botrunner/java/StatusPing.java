package com.uxplima.craftwire.botrunner.java;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.uxplima.craftwire.core.AgentError;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * Server List Ping, written out by hand because it runs before any version is loaded: a handshake with intent status
 * (protocol -1 is accepted for a status request) and a status request; the answer names the server's version and
 * protocol, which picks the version the bot joins with.
 */
final class StatusPing {
    record Version(String name, int protocol) {}

    private static final int TIMEOUT_MS = 5000;
    private static final int MAX_STATUS = 1 << 20;

    private StatusPing() {}

    static Version ping(String host, int port) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(host, port), TIMEOUT_MS);
            s.setSoTimeout(TIMEOUT_MS);
            OutputStream out = s.getOutputStream();
            ByteArrayOutputStream hs = new ByteArrayOutputStream();
            varInt(hs, 0x00);          // ClientIntentionPacket
            varInt(hs, -1);
            string(hs, host);
            hs.write((port >> 8) & 0xff);
            hs.write(port & 0xff);
            varInt(hs, 1);             // intent: status
            frame(out, hs.toByteArray());
            frame(out, new byte[] {0x00}); // ServerboundStatusRequestPacket
            DataInputStream in = new DataInputStream(s.getInputStream());
            readVarInt(in);                // frame length
            if (readVarInt(in) != 0x00) throw new IOException("not a status response");
            int len = readVarInt(in);
            if (len < 0 || len > MAX_STATUS) throw new IOException("status too long");
            byte[] json = in.readNBytes(len);
            JsonObject v = JsonParser.parseString(new String(json, StandardCharsets.UTF_8)).getAsJsonObject().getAsJsonObject("version");
            return new Version(v.get("name").getAsString(), v.get("protocol").getAsInt());
        } catch (IOException | RuntimeException e) {
            throw new AgentError("SERVER_UNREACHABLE", "Could not ping " + host + ":" + port + ": " + e.getMessage(),
                    "Check the address and that the server runs; or pass version to skip the ping.");
        }
    }

    private static void frame(OutputStream out, byte[] body) throws IOException {
        ByteArrayOutputStream f = new ByteArrayOutputStream();
        varInt(f, body.length);
        f.write(body);
        out.write(f.toByteArray());
        out.flush();
    }

    private static void string(ByteArrayOutputStream out, String s) {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        varInt(out, b.length);
        out.writeBytes(b);
    }

    private static void varInt(ByteArrayOutputStream out, int value) {
        int v = value;
        while ((v & ~0x7f) != 0) {
            out.write((v & 0x7f) | 0x80);
            v >>>= 7;
        }
        out.write(v);
    }

    private static int readVarInt(DataInputStream in) throws IOException {
        int value = 0;
        for (int shift = 0; shift < 35; shift += 7) {
            int b = in.readUnsignedByte();
            value |= (b & 0x7f) << shift;
            if ((b & 0x80) == 0) return value;
        }
        throw new IOException("varint too long");
    }
}
