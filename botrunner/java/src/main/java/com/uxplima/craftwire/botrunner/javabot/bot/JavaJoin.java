package com.uxplima.craftwire.botrunner.javabot.bot;

import com.google.gson.JsonObject;
import com.uxplima.craftwire.bot.json.Params;
import com.uxplima.craftwire.bot.model.ClientSettings;
import com.uxplima.craftwire.bot.model.Profile;
import com.uxplima.craftwire.botrunner.bot.JoinSpec;
import com.uxplima.craftwire.botrunner.bot.RunnerBot;
import com.uxplima.craftwire.botrunner.javabot.JavaContext;
import com.uxplima.craftwire.botrunner.javabot.session.JavaSession;
import com.uxplima.craftwire.core.AgentError;
import java.net.ConnectException;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import net.minecraft.core.UUIDUtil;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ParticleStatus;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.player.ChatVisiblity;

/**
 * Starting one Java bot: who it plays as (offline, or the signed-in account's session the hub passed), what it
 * tells the server about its client, the connection, a join timeout, and join failures as agent errors.
 */
final class JavaJoin {
    private static final long TIMEOUT_MS = 60_000;
    /** The light profile's view distance, as the plugin bot uses; the server clamps it to its own. */
    private static final int LIGHT_VIEW = 3;
    private static final int REALISTIC_VIEW = 10;

    private JavaJoin() {}

    static CompletableFuture<RunnerBot> start(JavaContext ctx, JoinSpec spec) {
        JavaSession.Identity id = identity(spec);
        JavaBot bot = new JavaBot(ctx, spec, id.uuid());
        ClientInformation info = information(spec.settings(), spec.profile());
        JavaSession.open(ctx.group(), spec.host(), spec.port(), ctx.protocol(), id, info, spec.settings().brand(), bot,
                () -> bot.creative, ctx.blocking()).whenComplete((s, err) -> {
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

    /** raw.session {name, uuid, accessToken} from the hub for a signed-in account, else an offline player of that name. */
    private static JavaSession.Identity identity(JoinSpec spec) {
        if (spec.raw().has("session") && spec.raw().get("session").isJsonObject()) {
            JsonObject s = spec.raw().getAsJsonObject("session");
            return new JavaSession.Identity(Params.string(s, "name"), uuid(Params.string(s, "uuid")), Params.string(s, "accessToken"));
        }
        return new JavaSession.Identity(spec.name(), UUIDUtil.createOfflinePlayerUUID(spec.name()), null);
    }

    /** Profile ids come without dashes from the Minecraft services. */
    private static UUID uuid(String s) {
        if (s.length() == 32) {
            s = s.substring(0, 8) + "-" + s.substring(8, 12) + "-" + s.substring(12, 16) + "-" + s.substring(16, 20) + "-" + s.substring(20);
        }
        return UUID.fromString(s);
    }

    static ClientInformation information(ClientSettings s, Profile profile) {
        int view = s.viewDistance() > 0 ? s.viewDistance() : profile == Profile.LIGHT ? LIGHT_VIEW : REALISTIC_VIEW;
        ChatVisiblity chat = ChatVisiblity.valueOf(s.chatVisibility().toUpperCase(Locale.ROOT));
        HumanoidArm hand = s.mainHand().equals("left") ? HumanoidArm.LEFT : HumanoidArm.RIGHT;
        return new ClientInformation(s.locale(), view, chat, true, s.skinParts(), hand, false, true, ParticleStatus.ALL);
    }

    /** Why a bot left before it was in the world, as an agent error. */
    static AgentError failure(String reason) {
        if (reason.startsWith("kicked: ")) {
            return new AgentError("JOIN_KICKED", "The server refused the bot: " + reason.substring(8),
                    "Check whitelist, bans, the server's version and anti-bot plugins; logs shows the server side.");
        }
        if (reason.contains("online mode")) {
            return new AgentError("ONLINE_MODE", reason, "client_process {action:'sign_in'} adds an account; spawn with account:<name>.");
        }
        return new AgentError("JOIN_FAILED", "The bot could not join: " + reason, "Check the address and that the server is up and reachable.");
    }

    private static AgentError unreachable(JoinSpec spec, Throwable err) {
        Throwable t = err instanceof CompletionException && err.getCause() != null ? err.getCause() : err;
        String at = spec.host() + ":" + spec.port();
        if (t instanceof UnknownHostException) {
            return new AgentError("SERVER_UNREACHABLE", "Unknown host " + spec.host(), "Check the address (host or host:port).");
        }
        if (t instanceof ConnectException) {
            return new AgentError("SERVER_UNREACHABLE", "Nothing accepts connections at " + at, "Start the server, or check the port.");
        }
        return new AgentError("SERVER_UNREACHABLE", "Could not connect to " + at + ": " + t.getMessage(), "Check the address and the network.");
    }
}
