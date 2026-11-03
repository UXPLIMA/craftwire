package com.uxplima.craftwire.paper.bot;

import com.uxplima.craftwire.bot.inbox.Inbox;
import com.uxplima.craftwire.bot.resourcepack.PackLog;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.network.chat.ClickEvent;

/**
 * Everything the bot's connection writes into while the server sends it packets: the inbox, the HUD copy, the
 * screens, the resource pack log, the pending teleport, and tasks to run on the bot's own thread at its next tick
 * (answers the server expects from the player's thread). Thread-safe: plugins send packets from any thread.
 */
final class BotLink {
    final Inbox inbox = new Inbox(200);
    final BotHud hud = new BotHud();
    final BotScreens screens = new BotScreens();
    final PackLog packs = new PackLog();
    final AtomicInteger pendingTeleport = new AtomicInteger(-1);
    private final Queue<Runnable> tasks = new ConcurrentLinkedQueue<>();
    /**
     * Whether the tab list is copied. Every join and leave sends each player a tab update, so n bots means n x n row
     * updates to copy; light bots start copying on their first hud_read.
     */
    private volatile boolean tabWatched;
    private final BotPacks packAnswers;
    /** The click events of recent clickable messages by message id, so chat_click runs them with payloads and dialogs. */
    private final Map<Long, List<ClickEvent>> clicks = new LinkedHashMap<>(16, 0.75f, false) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, List<ClickEvent>> eldest) {
            return size() > 64;
        }
    };

    BotLink(boolean tabWatched, BotPacks packAnswers) {
        this.tabWatched = tabWatched;
        this.packAnswers = packAnswers;
    }

    boolean tabWatched() {
        return tabWatched;
    }

    /** Starts copying the tab list; true when it was off (the caller seeds it from the server's current list). */
    boolean watchTab() {
        boolean was = tabWatched;
        tabWatched = true;
        return !was;
    }

    BotPacks packAnswers() {
        return packAnswers;
    }

    void rememberClicks(long messageId, List<ClickEvent> events) {
        synchronized (clicks) {
            clicks.put(messageId, events);
        }
    }

    /** The click events of a message, or an empty list once it is too old. */
    List<ClickEvent> clicks(long messageId) {
        synchronized (clicks) {
            return clicks.getOrDefault(messageId, List.of());
        }
    }

    /** Runs on the bot's thread at its next tick. */
    void later(Runnable task) {
        tasks.add(task);
    }

    /** Bot thread: runs the queued tasks (at most 32 a tick, so a flood of packets cannot stall the tick). */
    void runTasks() {
        Runnable r;
        int budget = 32;
        while (budget-- > 0 && (r = tasks.poll()) != null) r.run();
    }
}
