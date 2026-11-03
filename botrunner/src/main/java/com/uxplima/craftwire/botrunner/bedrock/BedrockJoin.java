package com.uxplima.craftwire.botrunner.bedrock;

import com.google.gson.JsonObject;
import com.uxplima.craftwire.bedrock.auth.ClientData;
import com.uxplima.craftwire.bedrock.auth.LoginRequest;
import com.uxplima.craftwire.bedrock.auth.MultiplayerToken;
import com.uxplima.craftwire.bedrock.auth.XboxChain;
import com.uxplima.craftwire.bedrock.crypto.Jwt;
import com.uxplima.craftwire.bedrock.packet.BedrockVersion;
import com.uxplima.craftwire.bedrock.raknet.RakPing;
import com.uxplima.craftwire.bot.json.Params;
import com.uxplima.craftwire.bot.model.Profile;
import com.uxplima.craftwire.botrunner.bot.JoinSpec;
import com.uxplima.craftwire.botrunner.bot.RunnerBot;
import com.uxplima.craftwire.botrunner.net.NetSim;
import com.uxplima.craftwire.core.AgentError;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;

/**
 * Starting one Bedrock bot: its identity (offline, or for an account the XSTS tokens the hub passed as raw.xbox
 * {userHash, xsts, playfab {userHash, xsts}}, which get the login chain and the multiplayer token), the client data
 * it presents, the Bedrock version (from the server's pong), the connection, a join timeout and join failures as
 * agent errors. The Xbox requests block, so the setup after the ping runs on the blocking executor.
 */
final class BedrockJoin {
    private static final long TIMEOUT_MS = 60_000;
    private static final long PING_MS = 3_000;
    private static final int LIGHT_VIEW = 4;
    private static final int REALISTIC_VIEW = 10;

    private BedrockJoin() {}

    static CompletableFuture<RunnerBot> start(BedrockContext ctx, JoinSpec spec) {
        UUID uuid = UUID.nameUUIDFromBytes(("BedrockBot:" + spec.name()).getBytes(StandardCharsets.UTF_8));
        BedrockBot bot = new BedrockBot(ctx, spec, uuid, view(spec));
        InetSocketAddress server = new InetSocketAddress(spec.host(), spec.port());
        version(ctx, server)
                .thenApplyAsync(v -> {
                    bot.version(v);
                    return login(spec, bot.key(), view(spec), v);
                }, ctx.blocking())
                .thenCompose(req -> BedrockSession.connect(ctx.group(), server, bot, req, bot.key(), bot.version(),
                        NetSim.from(spec.raw()).orElse(null)))
                .whenComplete((s, err) -> {
                    if (err == null) {
                        bot.session(s);
                        return;
                    }
                    ctx.services().loop().execute(() -> {
                        if (!bot.joined().isDone()) bot.joined().completeExceptionally(unreachable(spec, err));
                    });
                });
        CompletableFuture.delayedExecutor(TIMEOUT_MS, TimeUnit.MILLISECONDS).execute(() -> ctx.services().loop().execute(() -> {
            if (!bot.joined().isDone()) bot.leave("the join did not finish within " + TIMEOUT_MS / 1000 + " s");
        }));
        return bot.joined();
    }

    static int view(JoinSpec spec) {
        int v = spec.settings().viewDistance();
        return v > 0 ? v : spec.profile() == Profile.LIGHT ? LIGHT_VIEW : REALISTIC_VIEW;
    }

    /**
     * The version to speak, from the server's pong; a server that does not answer the ping gets the latest, and one
     * older than every version the bot has is refused before connecting.
     */
    private static CompletableFuture<BedrockVersion> version(BedrockContext ctx, InetSocketAddress server) {
        return RakPing.ping(ctx.group(), server, PING_MS).handle((status, err) -> {
            if (err != null || status.protocol() <= 0) return BedrockVersion.LATEST;
            return BedrockVersion.forServer(status.protocol()).orElseThrow(() -> new AgentError("VERSION_UNSUPPORTED",
                    "The server runs Bedrock " + status.version() + " (protocol " + status.protocol() + ")",
                    "Bedrock bots speak " + BedrockVersion.supported() + "; update the server or put Geyser in front of it."));
        });
    }

    /** The Login packet's connection request: offline, or signed by the Xbox chain when raw.xbox is given. */
    private static byte[] login(JoinSpec spec, KeyPair key, int view, BedrockVersion v) {
        String playFab = ClientData.randomPlayFabId();
        ClientData data = new ClientData(spec.name(), ClientData.language(spec.settings().locale()), spec.host() + ":" + spec.port(),
                v.game(), view);
        JsonObject clientData = data.json(playFab);
        JsonObject xbox = Params.optObject(spec.raw(), "xbox").orElse(null);
        if (xbox == null) {
            UUID identity = UUID.nameUUIDFromBytes(("pocket-auth-1-xuid:" + spec.name()).getBytes(StandardCharsets.UTF_8));
            return LoginRequest.offline(spec.name(), identity, playFab, key, clientData);
        }
        try {
            String chain = XboxChain.request(Params.string(xbox, "userHash"), Params.string(xbox, "xsts"), key, v.game());
            JsonObject playfab = Params.optObject(xbox, "playfab").orElse(null);
            String token = playfab == null ? null
                    : MultiplayerToken.request(Params.string(playfab, "userHash"), Params.string(playfab, "xsts"), key.getPublic(), v.game());
            return LoginRequest.xbox(chain, key, token, clientData);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Why a bot left before it was in the world, as an agent error. */
    static AgentError failure(String reason) {
        // Before the kick check: Geyser's "sign in to Xbox" kick is translated (it says "Xbox" in every language).
        if (reason.contains("Xbox") || reason.contains("not authenticated")) {
            String why = reason.startsWith("kicked: ") ? "The server wants Xbox sign-in: " + reason.substring(8) : reason;
            return new AgentError("ONLINE_MODE", why, "client_process {action:'sign_in'} adds a Microsoft account; spawn with account:<name>. "
                    + "On a Geyser server you run, validate-bedrock-login: false lets offline bots in.");
        }
        if (reason.startsWith("kicked: ")) {
            return new AgentError("JOIN_KICKED", "The server refused the bot: " + reason.substring(8),
                    "Check whitelist, bans, the server's version (bots speak Bedrock " + BedrockVersion.supported() + ") and anti-bot plugins.");
        }
        return new AgentError("JOIN_FAILED", "The bot could not join: " + reason, "Check the address and that the Bedrock server is up.");
    }

    private static AgentError unreachable(JoinSpec spec, Throwable err) {
        Throwable t = err instanceof CompletionException && err.getCause() != null ? err.getCause() : err;
        if (t instanceof AgentError a) return a;
        String at = spec.host() + ":" + spec.port();
        if (t instanceof UncheckedIOException u) {
            return new AgentError("ONLINE_MODE", "Xbox sign-in for Bedrock failed: " + u.getCause().getMessage(),
                    "Sign the account in again (client_process {action:'sign_in'}), then spawn again.");
        }
        return new AgentError("SERVER_UNREACHABLE", "Could not reach the Bedrock server at " + at + ": " + t.getMessage(),
                "Check the address and that the server listens on that UDP port (19132 by default).");
    }
}
