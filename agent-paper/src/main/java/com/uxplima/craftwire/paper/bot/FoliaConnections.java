package com.uxplima.craftwire.paper.bot;

import com.uxplima.craftwire.paper.Sync;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import net.minecraft.network.Connection;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * Folia ticks every player connection from the player's region, and that tick runs the player's movement and then
 * puts the player back where its last movement packet said (a real client moves itself). A bot sends no movement
 * packets, so on Folia its connection is taken off the region's list and the bot ticks itself, as on Paper. The
 * connection goes back on the list when the bot is leaving, so Folia's own disconnect handling cleans up.
 * Reflection, because the Paper API this plugin compiles against has no Folia internals. No-ops on Paper.
 * Owning thread of the player only.
 */
final class FoliaConnections {
    private static final MethodHandle WORLD_DATA;
    private static final MethodHandle ADD;
    private static final MethodHandle REMOVE;
    private static final MethodHandle HAS;
    private static final Field DISCONNECT_REQS;

    static {
        MethodHandle worldData = null, add = null, remove = null, has = null;
        Field disconnectReqs = null;
        if (Sync.folia()) {
            try {
                MethodHandles.Lookup l = MethodHandles.publicLookup();
                Class<?> data = Class.forName("io.papermc.paper.threadedregions.RegionizedWorldData");
                worldData = l.findVirtual(ServerLevel.class, "getCurrentWorldData", MethodType.methodType(data));
                add = l.findVirtual(data, "addConnection", MethodType.methodType(void.class, ServerPlayer.class));
                remove = l.findVirtual(data, "removeConnection", MethodType.methodType(void.class, ServerPlayer.class));
                has = l.findVirtual(data, "hasConnection", MethodType.methodType(boolean.class, Connection.class));
                disconnectReqs = Connection.class.getDeclaredField("disconnectReqs");
                disconnectReqs.setAccessible(true);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("This Folia build is not supported by Craftwire bots", e);
            }
        }
        WORLD_DATA = worldData;
        ADD = add;
        REMOVE = remove;
        HAS = has;
        DISCONNECT_REQS = disconnectReqs;
    }

    private FoliaConnections() {}

    /** Takes the bot's connection off its region's list, if a join, respawn or world change put it there. */
    static void detach(ServerPlayer player, Connection connection) {
        if (WORLD_DATA == null) return;
        try {
            Object data = WORLD_DATA.invoke((ServerLevel) player.level());
            if ((boolean) HAS.invoke(data, connection)) REMOVE.invoke(data, player);
        } catch (Throwable t) {
            throw new IllegalStateException("Could not detach " + player.getPlainTextName() + "'s connection", t);
        }
    }

    /**
     * A kick from another thread is queued on the connection for its next tick (Folia). The bot's connection is not
     * ticked by the region, so the bot takes the request here and runs it on its own thread. Null when none.
     */
    static DisconnectionDetails pollDisconnect(Connection connection) {
        if (DISCONNECT_REQS == null) return null;
        try {
            Object queue = DISCONNECT_REQS.get(connection);
            Object req = queue.getClass().getMethod("poll").invoke(queue);
            if (req == null) return null;
            Method details = req.getClass().getDeclaredMethod("details");
            details.setAccessible(true);
            return (DisconnectionDetails) details.invoke(req);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Could not read a queued disconnect", e);
        }
    }

    /** Puts it back, so the region handles the disconnect (player removal, chunk ticket clean-up). */
    static void attach(ServerPlayer player, Connection connection) {
        if (WORLD_DATA == null) return;
        try {
            Object data = WORLD_DATA.invoke((ServerLevel) player.level());
            if (!(boolean) HAS.invoke(data, connection)) ADD.invoke(data, player);
        } catch (Throwable t) {
            throw new IllegalStateException("Could not attach " + player.getPlainTextName() + "'s connection", t);
        }
    }
}
