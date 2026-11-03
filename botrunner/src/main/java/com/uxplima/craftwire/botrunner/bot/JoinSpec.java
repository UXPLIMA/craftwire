package com.uxplima.craftwire.botrunner.bot;

import com.google.gson.JsonObject;
import com.uxplima.craftwire.bot.model.ClientSettings;
import com.uxplima.craftwire.bot.model.Profile;
import com.uxplima.craftwire.bot.resourcepack.PackPolicy;

/**
 * One bot to join, as a driver needs it.
 *
 * @param host     server host
 * @param port     server port (25565 for Java, 19132 for Bedrock when the address had none)
 * @param account  a signed-in account name for online-mode servers, or null for offline
 * @param raw      the whole bot.spawn request, for kind-specific options (version, world mode, network simulation)
 */
public record JoinSpec(String name, String group, String host, int port, Profile profile, ClientSettings settings, PackPolicy packs,
                       boolean verifyPacks, String account, JsonObject raw) {
    /** host or host:port; IPv6 in brackets ([::1]:25565). */
    public static String[] split(String address, int defaultPort) {
        String a = address.trim();
        if (a.startsWith("[")) {
            int close = a.indexOf(']');
            String host = a.substring(1, close);
            String rest = a.substring(close + 1);
            return new String[] {host, rest.startsWith(":") ? rest.substring(1) : Integer.toString(defaultPort)};
        }
        int colon = a.lastIndexOf(':');
        if (colon < 0 || a.indexOf(':') != colon) return new String[] {a, Integer.toString(defaultPort)};
        return new String[] {a.substring(0, colon), a.substring(colon + 1)};
    }
}
