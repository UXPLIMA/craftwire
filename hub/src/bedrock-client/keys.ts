/**
 * Key names the bedrock_client tool takes, as Windows virtual-key codes (table-like data). Extended keys (arrows,
 * page and editing keys) need the extended flag when sent as scan codes.
 */
import { CraftwireError } from "../errors.js";

const NAMED: Record<string, number> = {
  enter: 0x0d, escape: 0x1b, space: 0x20, tab: 0x09, backspace: 0x08, shift: 0x10, ctrl: 0x11, alt: 0x12,
  left: 0x25, up: 0x26, right: 0x27, down: 0x28, home: 0x24, end: 0x23, pageup: 0x21, pagedown: 0x22, delete: 0x2e,
  slash: 0xbf, minus: 0xbd, equals: 0xbb, comma: 0xbc, period: 0xbe,
};
const EXTENDED = new Set(["left", "up", "right", "down", "home", "end", "pageup", "pagedown", "delete"]);

export interface VirtualKey {
  vk: number;
  extended: boolean;
}

/** A key by name: a letter, a digit, f1-f12 or one of the named keys. */
export function virtualKey(name: string): VirtualKey {
  const k = name.toLowerCase();
  if (/^[a-z]$/.test(k)) return { vk: k.toUpperCase().charCodeAt(0), extended: false };
  if (/^[0-9]$/.test(k)) return { vk: k.charCodeAt(0), extended: false };
  const f = /^f([1-9]|1[0-2])$/.exec(k);
  if (f !== null) return { vk: 0x6f + Number(f[1]), extended: false };
  const vk = NAMED[k];
  if (vk === undefined) {
    throw new CraftwireError("INVALID_PARAMS", `"${name}" is not a key name`,
      `Use a letter, a digit, f1-f12 or one of: ${Object.keys(NAMED).join(", ")}.`);
  }
  return { vk, extended: EXTENDED.has(k) };
}
