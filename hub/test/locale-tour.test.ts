/**
 * locale_tour against a fake Paper agent whose shop plugin translates by the bot's client locale: what it reports
 * must be the plugin's real translation gaps, not texts that are the same in every language by nature (the server's
 * address, vanilla item names, numbers).
 */
import { afterEach, describe, expect, it } from "vitest";
import { connectFakeAgent } from "./helpers/fakeAgent.js";
import { json, startHub } from "./helpers/hub.js";

/** The plugin's language files: tr_tr is missing one lore line and one message, de_de is complete. */
const LANG: Record<string, Record<string, string>> = {
  en_us: { title: "Shop", sword: "Sharp Sword", lore: "Costs 5 coins", reply: "Opened the shop", side: "Coins: 5" },
  tr_tr: { title: "Market", sword: "Keskin Kılıç", lore: "Costs 5 coins", reply: "shop.reply.opened", side: "Para: 5" },
  de_de: { title: "Laden", sword: "Scharfes Schwert", lore: "Kostet 5 Münzen", reply: "Laden geöffnet", side: "Münzen: 5" },
};

function shopFor(locale: string) {
  const t = LANG[locale]!;
  return {
    gui: {
      open: true, title: t.title, slotCount: 63,
      slots: [
        { slot: 10, id: "minecraft:diamond_sword", count: 1, name: t.sword, lore: [t.lore, "10/10"], container: "menu" },
        // a vanilla item without a custom name: its name is the same in every locale on the server side
        { slot: 12, id: "minecraft:oak_log", count: 16, name: "Oak Log", container: "menu" },
        { slot: 14, id: "minecraft:golden_apple", count: 1, name: "item.minecraft.golden_apple", container: "menu" },
        { slot: 30, id: "minecraft:bread", count: 3, name: "Bread", container: "player" },
      ],
    },
    reply: t.reply,
    hud: { sidebar: { title: "SkyPvP", entries: [{ shown: t.side }, { shown: "play.example.net" }] } },
  };
}

let hub: Awaited<ReturnType<typeof startHub>> | undefined;
afterEach(async () => {
  await hub?.close();
  hub = undefined;
});

async function tour(locales: string[]) {
  hub = await startHub();
  const agent = await connectFakeAgent(hub.port, { token: hub.token, kind: "server", name: "srv" });
  const localeOf = new Map<string, string>();
  agent.onRequest("bot.spawn", (p) => {
    const name = (p.names as string[])[0]!;
    localeOf.set(name, String((p.settings as { locale: string }).locale));
    return { spawned: 1, names: name };
  });
  agent.onRequest("bot.remove", () => ({ removed: 1 }));
  agent.onRequest("bot.action", (p) => {
    const shop = shopFor(localeOf.get(String(p.bot))!);
    switch (p.action) {
      case "command": return { sent: true };
      case "gui_read": return shop.gui;
      case "gui_close": return { closed: true };
      case "messages": return { messages: [{ text: shop.reply }] };
      case "hud_read": return shop.hud;
      default: throw new Error(`unexpected ${String(p.action)}`);
    }
  });
  await hub.call("more_tools", { enable: ["locale_tour"] });
  const r = await hub.call("locale_tour", { locales, targets: [{ name: "shop", command: "shop" }], waitMs: 100 });
  if (r.isError) throw new Error(JSON.stringify(r.content));
  return json(r) as {
    rawKeysInBase?: unknown[];
    locales: { locale: string; untranslated: { where: string; text: string }[]; rawKeys: { where: string; text: string }[]; notSeen?: string[] }[];
  };
}

describe("locale_tour", () => {
  it("lists the plugin's untranslated texts and leftover keys, and nothing that is the same in every language", async () => {
    const r = await tour(["tr_tr", "de_de"]);
    const tr = r.locales.find((l) => l.locale === "tr_tr")!;
    expect(tr.untranslated.map((u) => u.where)).toEqual(["shop: slot 10 lore 0", "shop: sidebar title"]);
    expect(tr.rawKeys).toEqual([{ where: "shop: message 0", text: "shop.reply.opened" }]);
    const de = r.locales.find((l) => l.locale === "de_de")!;
    expect(de.untranslated.map((u) => u.where)).toEqual(["shop: sidebar title"]);
    expect(de.rawKeys).toEqual([]);
    expect(r.rawKeysInBase).toBeUndefined();
  }, 30_000);
});
