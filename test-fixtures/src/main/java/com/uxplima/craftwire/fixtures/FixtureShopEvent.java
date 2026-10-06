package com.uxplima.craftwire.fixtures;

import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerEvent;

/** A plugin's own event, fired by /cwfixture buy: nothing touches the class before that first call. */
public final class FixtureShopEvent extends PlayerEvent implements Cancellable {
    private static final HandlerList HANDLERS = new HandlerList();
    private final int price;
    private boolean cancelled;

    public FixtureShopEvent(Player who, int price) {
        super(who);
        this.price = price;
    }

    public int getPrice() { return price; }
    public String getItem() { return "diamond"; }
    @Override public boolean isCancelled() { return cancelled; }
    @Override public void setCancelled(boolean cancel) { cancelled = cancel; }
    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
