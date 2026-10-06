import { createHash } from "node:crypto";
import type { FabricProfile } from "./fabric.js";
import { argumentList, type Os, type VersionJson } from "./mojang.js";

export interface LaunchInput {
  version: VersionJson;
  fabric: FabricProfile;
  os: Os;
  java: string;
  /** Libraries, then the client jar. */
  classpath: string[];
  pathSep: string;
  gameDir: string;
  assetsDir: string;
  nativesDir: string;
  libraryDir: string;
  /** The log4j config from the version's `logging.client`, when downloaded. */
  logConfig?: string;
  username: string;
  /** host:port to join right away (quick play). */
  server?: string;
  width: number;
  height: number;
  hidden: boolean;
  launcherVersion: string;
}

/** The UUID a server gives an offline-mode player: v3 of "OfflinePlayer:<name>", undashed as launchers pass it. */
export function offlineUuid(name: string): string {
  const h = createHash("md5").update(`OfflinePlayer:${name}`, "utf8").digest();
  h[6] = (h[6]! & 0x0f) | 0x30;
  h[8] = (h[8]! & 0x3f) | 0x80;
  return h.toString("hex");
}

/** The java command line for a Fabric client: Mojang's argument templates with Fabric's profile on top. */
export function buildLaunch(i: LaunchInput): { command: string; args: string[] } {
  const values: Record<string, string> = {
    auth_player_name: i.username,
    version_name: i.version.id,
    game_directory: i.gameDir,
    assets_root: i.assetsDir,
    assets_index_name: i.version.assetIndex.id,
    auth_uuid: offlineUuid(i.username),
    auth_access_token: "0",
    clientid: "",
    auth_xuid: "",
    version_type: i.version.type,
    natives_directory: i.nativesDir,
    launcher_name: "craftwire",
    launcher_version: i.launcherVersion,
    classpath: i.classpath.join(i.pathSep),
    classpath_separator: i.pathSep,
    library_directory: i.libraryDir,
    resolution_width: String(i.width),
    resolution_height: String(i.height),
    quickPlayMultiplayer: i.server ?? "",
  };
  const fill = (s: string) => s.replace(/\$\{(\w+)\}/g, (_, k: string) => values[k] ?? "");

  const jvm = [
    ...argumentList(i.version.arguments.jvm, i.os, {}).map(fill),
    ...(i.fabric.arguments.jvm ?? []),
    ...(i.logConfig && i.version.logging?.client ? [i.version.logging.client.argument.replace("${path}", i.logConfig)] : []),
    "-Xmx2G",
    // Windows JVMs write a pipe in the ANSI code page; the hub decodes UTF-8.
    "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8",
    // Hidden: Fabric must not open its error window (nobody can close it); the game exits and the crash is reported.
    ...(i.hidden ? ["-Dcraftwire.hidden=true", "-Dfabric.noGui=true"] : []),
  ];
  const features = { has_custom_resolution: true, is_quick_play_multiplayer: i.server !== undefined };
  const game = dropEmptyOptions([...argumentList(i.version.arguments.game, i.os, features), ...(i.fabric.arguments.game ?? [])].map(fill));
  return { command: i.java, args: [...jvm, i.fabric.mainClass, ...game] };
}

/** `--clientId ""` and the like: an option whose value came out empty is left out. */
function dropEmptyOptions(args: string[]): string[] {
  const out: string[] = [];
  for (let k = 0; k < args.length; k++) {
    if (args[k]!.startsWith("--") && args[k + 1] === "") {
      k++;
      continue;
    }
    out.push(args[k]!);
  }
  return out;
}
