// Stands in for a Paper server in hub tests: prints Paper's startup and Done lines and obeys "stop" on stdin.
// --mode=ok|agent|crash|hang|nostop. In agent mode it also connects to the hub the way the Craftwire plugin does.
import { readdirSync, readFileSync } from "node:fs";
import { join } from "node:path";
import { createInterface } from "node:readline";
import WebSocket from "ws";

const mode = (process.argv.find((a) => a.startsWith("--mode=")) ?? "--mode=ok").slice("--mode=".length);
const say = (line) => new Promise((resolve) => process.stdout.write(line + "\n", resolve));
let socket;

async function shutdown() {
  await say("Stopping server");
  socket?.close();
  process.exit(0);
}

function connectAgent() {
  const { port, token } = JSON.parse(readFileSync(join(process.env.CRAFTWIRE_HOME, "hub.json"), "utf8"));
  socket = new WebSocket(`ws://127.0.0.1:${port}/`);
  return new Promise((resolve, reject) => {
    socket.once("error", reject);
    socket.once("open", () => socket.send(JSON.stringify({
      jsonrpc: "2.0", id: 0, method: "hello",
      params: { token, agentKind: "server", agentVersion: "0.3.0", protocolVersion: 1, mcVersion: "26.2", instanceName: "fake", serverDir: process.cwd(), pid: process.pid },
    })));
    socket.on("message", (raw) => {
      const msg = JSON.parse(String(raw));
      if (msg.id === 0) return resolve();
      if (msg.method === undefined) return;
      const send = (body) => socket.send(JSON.stringify({ jsonrpc: "2.0", id: msg.id, ...body }));
      if (msg.method === "server.command" && msg.params.command === "stop") {
        send({ result: { command: "stop", success: true, output: [] } });
        void shutdown();
        return;
      }
      if (msg.method === "plugin.manage" && msg.params.action === "info") {
        const want = String(msg.params.name).toLowerCase();
        const jar = readdirSync("plugins").find((f) => f.toLowerCase().includes(want));
        if (!jar) return send({ error: { code: -32000, message: `No plugin named ${msg.params.name}`, data: { code: "PLUGIN_NOT_FOUND" } } });
        return send({ result: { name: msg.params.name, version: "1.0", enabled: true, jar } });
      }
      send({ error: { code: -32601, message: `unknown ${msg.method}`, data: { code: "UNKNOWN_METHOD" } } });
    });
  });
}

await say("Starting minecraft server version 26.2");
if (mode === "crash") {
  await say("**** FAILED TO BIND TO PORT!");
  process.exit(1);
}
createInterface({ input: process.stdin }).on("line", (line) => {
  if (line.trim() === "stop" && mode !== "nostop") void shutdown();
});
if (mode === "agent") await connectAgent();
if (mode !== "hang") await say('Done (1.234s)! For help, type "help"');
setInterval(() => {}, 1 << 30);
