/** Key names the bedrock_client tool takes, as the X keysym names xdotool sends (table-like data, as keys.ts for Windows). */
import { CraftwireError } from "../errors.js";

export const X11_NAMED: Record<string, string> = {
  enter: "Return", escape: "Escape", space: "space", tab: "Tab", backspace: "BackSpace", shift: "Shift_L", ctrl: "Control_L",
  alt: "Alt_L", left: "Left", up: "Up", right: "Right", down: "Down", home: "Home", end: "End", pageup: "Prior",
  pagedown: "Next", delete: "Delete", slash: "slash", minus: "minus", equals: "equal", comma: "comma", period: "period",
};

/** A key by name: a letter, a digit, f1-f12 or one of the named keys. */
export function keysym(name: string): string {
  const k = name.toLowerCase();
  if (/^[a-z0-9]$/.test(k)) return k;
  const f = /^f([1-9]|1[0-2])$/.exec(k);
  if (f !== null) return `F${f[1]}`;
  const sym = X11_NAMED[k];
  if (sym === undefined) {
    throw new CraftwireError("INVALID_PARAMS", `"${name}" is not a key name`,
      `Use a letter, a digit, f1-f12 or one of: ${Object.keys(X11_NAMED).join(", ")}.`);
  }
  return sym;
}
