package com.uxplima.craftwire.botrunner.bedrock;

import com.uxplima.craftwire.bedrock.packet.LoginOut;
import com.uxplima.craftwire.bedrock.packet.LoginPackets;
import com.uxplima.craftwire.bot.resourcepack.PackCheck;
import com.uxplima.craftwire.bot.resourcepack.PackLog;
import com.uxplima.craftwire.bot.resourcepack.PackOffer;
import com.uxplima.craftwire.bot.resourcepack.PackPolicy;
import com.uxplima.craftwire.bot.resourcepack.PackStatus;
import io.netty.buffer.ByteBuf;
import java.io.ByteArrayOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Resource packs while joining, the Bedrock way: the server lists its packs (ResourcePacksInfo), the client asks for
 * the ones it lacks and downloads them chunk by chunk through the connection (ResourcePackChunkRequest), then
 * confirms the pack stack. With `verify` the bot really downloads and checks each pack's SHA-256; otherwise it says
 * it has them all, as a client with a warm cache does. Declining (decline, invalid_url and the failure policies)
 * refuses the packs, which a server requiring them answers with a kick. Runner loop thread.
 */
final class BedrockPacks {
    private static final int MAX_PACK = 256 * 1024 * 1024;

    private final PackPolicy policy;
    private final boolean verify;
    private final Consumer<ByteBuf> out;
    private final PackLog log = new PackLog();
    private final Map<String, PackOffer> offers = new HashMap<>();
    private final Map<String, Download> downloads = new HashMap<>();

    private static final class Download {
        final LoginPackets.ResourcePackDataInfo info;
        final ByteArrayOutputStream data = new ByteArrayOutputStream();
        final long started;
        int next;

        Download(LoginPackets.ResourcePackDataInfo info, long started) {
            this.info = info;
            this.started = started;
        }
    }

    BedrockPacks(PackPolicy policy, boolean verify, Consumer<ByteBuf> out) {
        this.policy = policy;
        this.verify = verify;
        this.out = out;
    }

    PackLog log() {
        return log;
    }

    void info(LoginPackets.ResourcePacksInfo info, long now) {
        List<String> ids = new ArrayList<>();
        for (LoginPackets.PackInfo p : info.packs()) {
            String id = p.id() + "_" + p.version();
            String url = p.downloadUrl().isEmpty() ? null : p.downloadUrl();
            offers.put(id, log.offered(p.id(), url, null, info.required(), null, now));
            ids.add(id);
        }
        if (policy == PackPolicy.IGNORE) return;
        if (ids.isEmpty()) {
            out.accept(LoginOut.packResponse(LoginOut.PACKS_HAVE_ALL, List.of()));
            return;
        }
        if (policy != PackPolicy.ACCEPT) {
            answerAll(PackStatus.DECLINED);
            out.accept(LoginOut.packResponse(LoginOut.PACKS_REFUSED, List.of()));
            return;
        }
        answerAll(PackStatus.ACCEPTED);
        if (verify) {
            out.accept(LoginOut.packResponse(LoginOut.PACKS_SEND, ids));
            return;
        }
        answerAll(PackStatus.SUCCESSFULLY_LOADED);
        out.accept(LoginOut.packResponse(LoginOut.PACKS_HAVE_ALL, List.of()));
    }

    private void answerAll(PackStatus s) {
        for (PackOffer o : offers.values()) log.answered(o, s);
    }

    void dataInfo(LoginPackets.ResourcePackDataInfo d, long now) {
        if (d.size() > MAX_PACK || d.chunkCount() <= 0) {
            finish(d.id(), new PackCheck("too_large", null, d.size(), 0, "over " + MAX_PACK + " bytes or no chunks"));
            return;
        }
        Download dl = new Download(d, now);
        downloads.put(d.id(), dl);
        out.accept(LoginOut.packChunkRequest(d.id(), 0));
    }

    void chunk(LoginPackets.ResourcePackChunkData c, long now) {
        Download dl = downloads.get(c.id());
        if (dl == null || c.index() != dl.next) return;
        dl.data.writeBytes(c.data());
        dl.next++;
        if (dl.next < dl.info.chunkCount()) {
            out.accept(LoginOut.packChunkRequest(c.id(), dl.next));
            return;
        }
        downloads.remove(c.id());
        byte[] bytes = dl.data.toByteArray();
        String sha = sha256(bytes);
        String expected = HexFormat.of().formatHex(dl.info.hash());
        long ms = now - dl.started;
        PackCheck check = sha.equalsIgnoreCase(expected) ? new PackCheck("ok", sha, bytes.length, ms, "sha256")
                : new PackCheck("hash_mismatch", sha, bytes.length, ms, "offered " + expected);
        finish(c.id(), check);
    }

    private void finish(String id, PackCheck check) {
        PackOffer o = offers.remove(id);
        // Some servers name the pack by its UUID alone in ResourcePackDataInfo, without "_version".
        if (o == null) {
            String uuid = id.split("_", 2)[0];
            String key = offers.keySet().stream().filter(k -> k.startsWith(uuid)).findFirst().orElse(null);
            if (key != null) o = offers.remove(key);
        }
        if (o != null) {
            log.checked(o, check);
            log.answered(o, check.ok() ? PackStatus.SUCCESSFULLY_LOADED : PackStatus.FAILED_DOWNLOAD);
        }
        if (offers.isEmpty() && downloads.isEmpty()) out.accept(LoginOut.packResponse(LoginOut.PACKS_HAVE_ALL, List.of()));
    }

    /** ResourcePackStack: the order to apply the packs in; the client answers that it is done. */
    void stack() {
        if (policy == PackPolicy.IGNORE) return;
        out.accept(LoginOut.packResponse(LoginOut.PACKS_COMPLETED, List.of()));
    }

    private static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("the JDK has no SHA-256", e);
        }
    }
}
