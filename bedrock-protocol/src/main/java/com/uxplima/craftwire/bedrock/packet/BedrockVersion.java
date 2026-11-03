package com.uxplima.craftwire.bedrock.packet;

import java.util.Arrays;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * The Bedrock versions a bot speaks. The fields that differ between them are coded where they occur, each citing
 * the version that changed it; 1.26.60 (gophertunnel #541) added EditorLevelMigrationVersion to StartGame, moved
 * PlayFabID from Skin to PlayerListEntry, and added the hand to Animate and to the UseItemOnEntity and ReleaseItem
 * transactions.
 */
public enum BedrockVersion {
    V1_26_50(2193, "1.26.50"),
    V1_26_60(2225, "1.26.60");

    public static final BedrockVersion LATEST = V1_26_60;

    private final int protocol;
    private final String game;

    BedrockVersion(int protocol, String game) {
        this.protocol = protocol;
        this.game = game;
    }

    public int protocol() {
        return protocol;
    }

    /** The game version, as client data, the Xbox requests and the discovery service name it. */
    public String game() {
        return game;
    }

    /** True from 1.26.60 on: the hand fields, PlayFabID in PlayerListEntry, EditorLevelMigrationVersion. */
    public boolean has1_26_60() {
        return protocol >= V1_26_60.protocol;
    }

    public static Optional<BedrockVersion> of(int protocol) {
        return Arrays.stream(values()).filter(v -> v.protocol == protocol).findFirst();
    }

    /**
     * The version to speak to a server whose pong names `protocol`: the newest one not above it. A proxy such as
     * Geyser names its newest protocol and accepts older ones, so a server ahead of the bot still gets {@link #LATEST};
     * empty when the server is older than every version the bot has.
     */
    public static Optional<BedrockVersion> forServer(int protocol) {
        BedrockVersion pick = null;
        for (BedrockVersion v : values()) if (v.protocol <= protocol) pick = v;
        return Optional.ofNullable(pick);
    }

    /** "1.26.50 (2193), 1.26.60 (2225)", for errors that name what the bot can speak. */
    public static String supported() {
        return Arrays.stream(values()).map(v -> v.game + " (" + v.protocol + ")").collect(Collectors.joining(", "));
    }
}
