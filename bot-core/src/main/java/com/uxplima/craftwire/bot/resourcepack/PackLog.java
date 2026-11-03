package com.uxplima.craftwire.bot.resourcepack;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.uxplima.craftwire.bot.json.Jsons;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** The resource pack offers one bot got, for bot_action resource_packs. Thread-safe: network threads record offers. */
public final class PackLog {
    private static final int MAX = 32;

    private final List<PackOffer> offers = new ArrayList<>();
    private final List<String> removed = new ArrayList<>();

    public synchronized PackOffer offered(UUID id, String url, String hash, boolean required, String prompt, long time) {
        PackOffer o = new PackOffer(id, url, hash, required, prompt, time);
        offers.add(o);
        if (offers.size() > MAX) offers.removeFirst();
        return o;
    }

    public synchronized void answered(PackOffer o, PackStatus s) {
        o.answered(s);
    }

    public synchronized void checked(PackOffer o, PackCheck c) {
        o.checked(c);
    }

    /** ClientboundResourcePackPopPacket: the server took a pack back (`id`), or all of them (null). */
    public synchronized void popped(UUID id) {
        removed.add(id == null ? "all" : id.toString());
        if (removed.size() > MAX) removed.removeFirst();
    }

    public synchronized JsonObject json() {
        JsonArray a = new JsonArray();
        for (PackOffer o : offers) a.add(o.json());
        JsonObject r = new JsonObject();
        r.add("offers", a);
        if (!removed.isEmpty()) r.add("removedByServer", Jsons.strings(removed));
        return r;
    }

    public synchronized List<PackOffer> offers() {
        return new ArrayList<>(offers);
    }
}
