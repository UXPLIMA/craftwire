/** The types of client_process: start options, what the launcher is given, and the status a client reports. */
import type { AgentServer } from "../agents.js";
import type { Accounts } from "../auth/accounts.js";
import type { ModProfiles } from "./mod-profiles.js";
import type { McVersion } from "./pins.js";

export type ClientState = "downloading" | "starting" | "running" | "stopping" | "stopped" | "crashed";

/** How a new singleplayer world is made (the agent does it like the Create World screen). */
export interface WorldCreate {
  type?: "normal" | "flat" | "void";
  seed?: string;
  gameMode?: "survival" | "creative" | "adventure" | "spectator";
  difficulty?: "peaceful" | "easy" | "normal" | "hard";
  cheats?: boolean;
  /** Delete a save with this name first, for a fresh world on every run. */
  replace?: boolean;
}

export interface ClientStartOptions {
  /** host:port; default: the single server running under server_process. */
  server?: string;
  /** A singleplayer world to open (or create) instead of joining a server. */
  world?: { name: string; create?: WorldCreate };
  /** Minecraft version; default: the one the server reports, else the newest supported. */
  version?: McVersion;
  username?: string;
  mods?: string[];
  visible?: boolean;
  windowSize?: { width: number; height: number };
  java?: string;
  sounds?: boolean;
  timeoutMs?: number;
  /** No frames unless a capture needs one, a small heap, smallest view: for many hidden clients (always hidden). */
  lite?: boolean;
  /** How server resource packs are answered: accept, decline, or prompt (vanilla's screen). */
  resourcePacks?: ResourcePackPolicy;
  /** A signed-in Microsoft account (its Minecraft name) to play online-mode servers with. */
  account?: string;
  /** A saved mod profile whose mods are added to `mods`. */
  modProfile?: string;
}

export type ResourcePackPolicy = "accept" | "decline" | "prompt";

/** The Minecraft session of a signed-in account, as the launch arguments need it. */
export interface LaunchSession {
  name: string;
  uuid: string;
  accessToken: string;
  xuid?: string;
}

export interface PrepareInput {
  root: string;
  version: McVersion;
  username: string;
  server?: string;
  mods: string[];
  visible: boolean;
  width: number;
  height: number;
  java?: string;
  sounds: boolean;
  lite: boolean;
  resourcePacks: ResourcePackPolicy;
  session?: LaunchSession;
  onProgress: (done: number, total: number) => void;
}

/** Downloads what is missing and returns the command to start; the real one is launcher.ts's prepareClient. */
export type PrepareClient = (p: PrepareInput) => Promise<{ command: string; args: string[]; gameDir: string; downloadedBytes: number }>;

export interface ClientStatus {
  username: string;
  state: ClientState;
  version?: McVersion;
  instance?: string | null;
  server?: string;
  world?: string;
  pid?: number;
  gameDir?: string;
  progress?: { done: number; total: number };
  downloadedBytes?: number;
  startedAt?: number;
  readyMs?: number;
  exitCode?: number | null;
  lite?: boolean;
  account?: string;
  logTail: string[];
}

/** The part of ServerManager this needs: which managed servers run. */
export interface ServerLister {
  status(): { servers: { serverDir: string; state: string }[] };
}

export interface ClientManagerOptions {
  agents: AgentServer;
  /** CRAFTWIRE_HOME for the client, so its agent finds this hub. */
  home: string;
  /** Cache and instances (default `<home>/client`). */
  root?: string;
  servers: ServerLister;
  prepare: PrepareClient;
  /** How long stop waits for the game to quit before killing it (default 10 s). */
  stopTimeoutMs?: number;
  quitTimeoutMs?: number;
  readyPollMs?: number;
  /** Asks a server for its version (Server List Ping); injectable for tests. */
  ping?: (host: string, port: number) => Promise<{ name: string; protocol: number } | undefined>;
  /** Signed-in Microsoft accounts (default: under `home`). */
  accounts?: Accounts;
  /** Saved mod profiles (default: under the client root). */
  modProfiles?: ModProfiles;
}
