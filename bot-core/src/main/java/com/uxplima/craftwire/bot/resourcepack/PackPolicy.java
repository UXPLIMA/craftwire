package com.uxplima.craftwire.bot.resourcepack;

import com.uxplima.craftwire.bot.model.BotErrors;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * How a bot answers resource pack offers. Pack plugins (ItemsAdder, Oraxen, Nexo) kick or freeze players that
 * decline or never answer, so the default accepts like a vanilla client that loaded the pack.
 */
public enum PackPolicy {
    ACCEPT(List.of(PackStatus.ACCEPTED, PackStatus.DOWNLOADED, PackStatus.SUCCESSFULLY_LOADED)),
    DECLINE(List.of(PackStatus.DECLINED)),
    FAIL_DOWNLOAD(List.of(PackStatus.ACCEPTED, PackStatus.FAILED_DOWNLOAD)),
    INVALID_URL(List.of(PackStatus.INVALID_URL)),
    FAIL_RELOAD(List.of(PackStatus.ACCEPTED, PackStatus.DOWNLOADED, PackStatus.FAILED_RELOAD)),
    DISCARD(List.of(PackStatus.ACCEPTED, PackStatus.DOWNLOADED, PackStatus.DISCARDED)),
    /** No answer at all, like a client stuck on the prompt. */
    IGNORE(List.of());

    private final List<PackStatus> answers;

    PackPolicy(List<PackStatus> answers) {
        this.answers = answers;
    }

    /** The answers in the order a client sends them. */
    public List<PackStatus> answers() {
        return answers;
    }

    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static PackPolicy parse(String id) {
        if (id == null) return ACCEPT;
        for (PackPolicy p : values()) if (p.id().equals(id)) return p;
        throw BotErrors.invalid("resourcePack must be one of " + Arrays.stream(values()).map(PackPolicy::id).collect(Collectors.joining(", ")));
    }
}
