/** Doctor checks for Linux: what client_process needs without a display, and what bedrock_client needs (mcpelauncher, X tools). */
import { findLauncher, type Launcher } from "./bedrock-client/mcpelauncher.js";
import { onPath } from "./bedrock-client/x11-tools.js";
import type { Check } from "./doctor.js";
import { CraftwireError } from "./errors.js";

export interface LinuxCheckDeps {
  env: NodeJS.ProcessEnv;
  onPath: (name: string) => string | undefined;
  findLauncher: () => Launcher;
}

export function linuxChecks(deps: Partial<LinuxCheckDeps> = {}): Check[] {
  const env = deps.env ?? process.env;
  const d: LinuxCheckDeps = { env, onPath: (n) => onPath(n, env), findLauncher: () => findLauncher({ env }), ...deps };
  const checks: Check[] = [];
  const hasDisplay = Boolean(env.DISPLAY || env.WAYLAND_DISPLAY);
  const xvfb = d.onPath("Xvfb") !== undefined;
  if (!hasDisplay) {
    checks.push(xvfb
      ? { status: "ok", label: "no display: client_process runs the game under Xvfb" }
      : { status: "warn", label: "no display and no Xvfb: client_process cannot open the game", fix: "apt install xvfb libegl1 libegl-mesa0" });
  }
  let launcher: Launcher | undefined;
  try {
    launcher = d.findLauncher();
  } catch (e) {
    const hint = e instanceof CraftwireError ? e.hint : undefined;
    checks.push({ status: "warn", label: `bedrock_client: ${(e as Error).message}`, ...(hint === undefined ? {} : { fix: hint }) });
    return checks;
  }
  checks.push({ status: "ok", label: `bedrock_client: mcpelauncher with the game in ${launcher.gameDir}` });
  const missing: string[] = [];
  if (!xvfb && !env.CRAFTWIRE_BEDROCK_DISPLAY) missing.push("xvfb");
  if (d.onPath("xdotool") === undefined) missing.push("xdotool");
  if (d.onPath("import") === undefined && d.onPath("ffmpeg") === undefined) missing.push("imagemagick");
  checks.push(missing.length === 0
    ? { status: "ok", label: "bedrock_client: Xvfb, xdotool and a screen capture tool are installed" }
    : { status: "warn", label: `bedrock_client needs ${missing.join(", ")}`, fix: `apt install ${missing.join(" ")}` });
  return checks;
}
