package com.uxplima.craftwire.bot.resourcepack;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * One resource pack the server offered a bot (ClientboundResourcePackPushPacket), the answers the bot gave and, when
 * verified, what the download showed. Mutable as answers arrive; guarded by the owning {@link PackLog}.
 */
public final class PackOffer {
    private final UUID id;
    private final String url;
    private final String hash;
    private final boolean required;
    private final String prompt;
    private final long time;
    private final List<PackStatus> answers = new ArrayList<>();
    private PackCheck check;

    public PackOffer(UUID id, String url, String hash, boolean required, String prompt, long time) {
        this.id = id;
        this.url = url;
        this.hash = hash;
        this.required = required;
        this.prompt = prompt;
        this.time = time;
    }

    public UUID id() {
        return id;
    }

    public String url() {
        return url;
    }

    public String hash() {
        return hash;
    }

    void answered(PackStatus s) {
        answers.add(s);
    }

    void checked(PackCheck c) {
        check = c;
    }

    JsonObject json() {
        JsonObject o = new JsonObject();
        o.addProperty("id", id.toString());
        o.addProperty("url", url);
        if (hash != null && !hash.isEmpty()) o.addProperty("hash", hash);
        o.addProperty("required", required);
        if (prompt != null) o.addProperty("prompt", prompt);
        o.addProperty("time", time);
        JsonArray a = new JsonArray();
        answers.forEach(s -> a.add(s.id()));
        o.add("answers", a);
        if (check != null) o.add("download", check.json());
        return o;
    }
}
