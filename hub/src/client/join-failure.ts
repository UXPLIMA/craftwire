/** Why a client_process client could not join, read from its disconnect screen, with what to do about it. */

export interface DisconnectScreen {
  type?: string;
  title?: string;
  widgets?: { kind?: string; text?: string }[];
}

/** The server's reason from a disconnect screen (title plus message lines, without buttons) and how to fix it. */
export function joinFailure(screen: DisconnectScreen, username: string): { reason: string; hint: string } {
  const lines = (screen.widgets ?? [])
    .filter((w) => w.text && /Text|String/.test(w.kind ?? ""))
    .map((w) => w.text!)
    .filter((t) => t !== screen.title);
  const reason = [screen.title ?? screen.type, ...lines].filter(Boolean).join(": ");
  if (/whitelist/i.test(reason)) {
    return {
      reason,
      hint: `Ask the user to allow the player on this dev server: server_command "whitelist add ${username}", or white-list=false in `
        + "server.properties (servers since 26.3 whitelist by default).",
    };
  }
  if (/banned/i.test(reason)) return { reason, hint: `The player is banned on this server: server_command "pardon ${username}" if the user agrees.` };
  if (/outdated|incompatible|version/i.test(reason)) {
    return { reason, hint: "The server runs another Minecraft version: pass version, or let client_process detect it." };
  }
  return { reason, hint: "Check that the server runs, the address is right and online-mode=false." };
}
