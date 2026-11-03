package com.uxplima.craftwire.paper.bot;

import com.uxplima.craftwire.bot.resourcepack.PackCheck;
import com.uxplima.craftwire.bot.resourcepack.PackOffer;
import com.uxplima.craftwire.bot.resourcepack.PackPolicy;
import com.uxplima.craftwire.bot.resourcepack.PackStatus;
import com.uxplima.craftwire.bot.resourcepack.PackVerifier;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.network.protocol.common.ClientboundResourcePackPushPacket;
import net.minecraft.network.protocol.common.ServerboundResourcePackPacket;

/**
 * How one bot answers resource pack offers: by its policy, after downloading the pack once to check it when
 * `verify` is on (a broken pack then gets FAILED_DOWNLOAD or INVALID_URL, as a real client would send). Answers go
 * through the server's own handler on the bot's thread, so PlayerResourcePackStatusEvent fires for each.
 */
final class BotPacks {
    private final PackPolicy policy;
    private final boolean verify;
    private final PackVerifier verifier;
    private volatile Consumer<ServerboundResourcePackPacket> sink;

    BotPacks(PackPolicy policy, boolean verify, PackVerifier verifier) {
        this.policy = policy;
        this.verify = verify;
        this.verifier = verifier;
    }

    /** Where answers go once the bot's listener exists (joining creates it after the connection). */
    void sink(Consumer<ServerboundResourcePackPacket> s) {
        sink = s;
    }

    /** Any thread: records the offer and schedules the answers on the bot's thread. */
    void offered(BotLink link, ClientboundResourcePackPushPacket p, long now) {
        String prompt = p.prompt().map(c -> c.getString()).orElse(null);
        PackOffer offer = link.packs.offered(p.id(), p.url(), p.hash(), p.required(), prompt, now);
        if (policy == PackPolicy.IGNORE) return;
        if (!verify || policy != PackPolicy.ACCEPT) {
            answer(link, offer, policy.answers());
            return;
        }
        link.later(() -> send(link, offer, PackStatus.ACCEPTED));
        verifier.verify(p.url(), p.hash()).thenAccept(check -> {
            link.packs.checked(offer, check);
            answer(link, offer, afterDownload(check));
        });
    }

    /** What a vanilla client sends after its download: loaded, or why not. */
    private static List<PackStatus> afterDownload(PackCheck check) {
        if (check.ok()) return List.of(PackStatus.DOWNLOADED, PackStatus.SUCCESSFULLY_LOADED);
        if (check.result().equals("invalid_url")) return List.of(PackStatus.INVALID_URL);
        return List.of(PackStatus.FAILED_DOWNLOAD);
    }

    private void answer(BotLink link, PackOffer offer, List<PackStatus> statuses) {
        for (PackStatus s : statuses) link.later(() -> send(link, offer, s));
    }

    private void send(BotLink link, PackOffer offer, PackStatus s) {
        Consumer<ServerboundResourcePackPacket> out = sink;
        if (out == null) return;
        link.packs.answered(offer, s);
        out.accept(new ServerboundResourcePackPacket(offer.id(), ServerboundResourcePackPacket.Action.valueOf(s.name())));
    }
}
