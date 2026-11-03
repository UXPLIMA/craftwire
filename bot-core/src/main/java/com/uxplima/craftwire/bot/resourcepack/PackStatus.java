package com.uxplima.craftwire.bot.resourcepack;

import java.util.Locale;

/** The answers a client sends about a resource pack (ServerboundResourcePackPacket.Action), in protocol id order. */
public enum PackStatus {
    SUCCESSFULLY_LOADED,
    DECLINED,
    FAILED_DOWNLOAD,
    ACCEPTED,
    DOWNLOADED,
    INVALID_URL,
    FAILED_RELOAD,
    DISCARDED;

    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }
}
