package com.uxplima.craftwire.botrunner.javabot.bot;

import com.uxplima.craftwire.bot.resourcepack.PackCheck;
import com.uxplima.craftwire.bot.resourcepack.PackLog;
import com.uxplima.craftwire.bot.resourcepack.PackOffer;
import com.uxplima.craftwire.bot.resourcepack.PackPolicy;
import com.uxplima.craftwire.bot.resourcepack.PackStatus;
import com.uxplima.craftwire.bot.resourcepack.PackVerifier;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import net.minecraft.network.protocol.common.ClientboundResourcePackPushPacket;
import net.minecraft.network.protocol.common.ServerboundResourcePackPacket;

/**
 * How one Java bot answers resource pack offers, like the plugin bot: by its policy, after a real download and hash
 * check when `verify` is on (a broken pack gets FAILED_DOWNLOAD or INVALID_URL, as a vanilla client sends). Offers
 * arrive in configuration and in play; answers are valid in both, so they go out in whatever phase the connection
 * is in. Runner loop thread; download results come back through `loop`.
 */
final class JavaPacks {
    private final PackPolicy policy;
    private final boolean verify;
    private final PackVerifier verifier;
    private final Executor loop;
    private final Consumer<ServerboundResourcePackPacket> out;
    private final PackLog log = new PackLog();

    JavaPacks(PackPolicy policy, boolean verify, PackVerifier verifier, Executor loop, Consumer<ServerboundResourcePackPacket> out) {
        this.policy = policy;
        this.verify = verify;
        this.verifier = verifier;
        this.loop = loop;
        this.out = out;
    }

    PackLog log() {
        return log;
    }

    void offered(ClientboundResourcePackPushPacket p, long now) {
        String prompt = p.prompt().map(c -> c.getString()).orElse(null);
        PackOffer offer = log.offered(p.id(), p.url(), p.hash(), p.required(), prompt, now);
        if (policy == PackPolicy.IGNORE) return;
        if (!verify || policy != PackPolicy.ACCEPT) {
            for (PackStatus s : policy.answers()) send(offer, s);
            return;
        }
        send(offer, PackStatus.ACCEPTED);
        verifier.verify(p.url(), p.hash()).thenAcceptAsync(check -> {
            log.checked(offer, check);
            for (PackStatus s : afterDownload(check)) send(offer, s);
        }, loop);
    }

    /** What a vanilla client sends after its download: loaded, or why not. */
    private static List<PackStatus> afterDownload(PackCheck check) {
        if (check.ok()) return List.of(PackStatus.DOWNLOADED, PackStatus.SUCCESSFULLY_LOADED);
        if (check.result().equals("invalid_url")) return List.of(PackStatus.INVALID_URL);
        return List.of(PackStatus.FAILED_DOWNLOAD);
    }

    private void send(PackOffer offer, PackStatus s) {
        log.answered(offer, s);
        out.accept(new ServerboundResourcePackPacket(offer.id(), ServerboundResourcePackPacket.Action.valueOf(s.name())));
    }
}
