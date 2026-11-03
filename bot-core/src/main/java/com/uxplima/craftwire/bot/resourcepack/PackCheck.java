package com.uxplima.craftwire.bot.resourcepack;

import com.google.gson.JsonObject;

/**
 * What downloading an offered pack showed.
 *
 * @param result ok, invalid_url, http_&lt;code&gt;, timeout, too_large, hash_mismatch or error
 * @param sha1   the SHA-1 of the downloaded file, or null
 */
public record PackCheck(String result, String sha1, long size, long ms, String detail) {
    public boolean ok() {
        return result.equals("ok");
    }

    public JsonObject json() {
        JsonObject o = new JsonObject();
        o.addProperty("result", result);
        if (sha1 != null) o.addProperty("sha1", sha1);
        if (size > 0) o.addProperty("size", size);
        o.addProperty("ms", ms);
        if (detail != null) o.addProperty("detail", detail);
        return o;
    }
}
