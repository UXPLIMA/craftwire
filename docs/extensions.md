# Adding your own tools

A Paper plugin or a Fabric mod can give the AI its own tools. They appear next to the built-in ones as
`<namespace>_<name>`. The namespace is your plugin's name or your mod's id, in lowercase. For example, a plugin
`MyShop` that registers `give_coins` adds `myshop_give_coins`.

Use this for things only your code knows how to do well: reading your plugin's state ("how many coins does Steve
have?"), setting up a test situation in one call ("open an auction with these items"), or checking an invariant.

## The dependency

The API is a small jar with no dependencies (Java 21). Compile against it, but do not bundle it: Craftwire brings
these classes at runtime.

```groovy
repositories { maven { url = 'https://jitpack.io' } }
dependencies { compileOnly 'com.github.uxplima.craftwire:craftwire-api:<version>' }
```

To build it from a checkout instead, run `./gradlew -PapiOnly :craftwire-api:build` (Java 21 is enough); the jar is
in `craftwire-api/build/libs`.

## A tool

```java
public final class CoinsTool implements CraftwireTool {
    private final Economy economy;

    public CoinsTool(Economy economy) { this.economy = economy; }

    @Override public String name() { return "coins"; }

    @Override public String description() {
        return "How many coins a player has, and their last 5 transactions.";
    }

    @Override public String inputSchema() {
        return """
            {"type": "object",
             "properties": {"player": {"type": "string", "description": "Player name"}},
             "required": ["player"]}""";
    }

    @Override public String call(String arguments) {
        String player = JsonParser.parseString(arguments).getAsJsonObject().get("player").getAsString();
        Account a = economy.account(player);
        if (a == null) throw new ToolException("NO_ACCOUNT", player + " has no account", "They need to join once first.");
        JsonObject r = new JsonObject();
        r.addProperty("coins", a.balance());
        r.add("recent", a.recentAsJson(5));
        return r.toString();
    }
}
```

- `call` gets the arguments as a JSON object and returns JSON. Gson ships with Minecraft and Paper.
- `call` runs on the game thread (the server's main thread, or the client's render thread), so you can use the
  game's API directly. Return `false` from `onGameThread()` for slow work that does not touch the game.
- Throw `ToolException(code, message, hint)` for failures the AI can act on. Any other exception is reported as a
  bug in your tool, and its stack trace goes to the log and the `exceptions` tool.
- Write the description and the schema's descriptions for the AI: say what the tool returns and when to use it.

## Paper

Add `softdepend: [Craftwire]` to `plugin.yml`, then register in `onEnable`:

```java
@Override public void onEnable() {
    if (getServer().getPluginManager().getPlugin("Craftwire") != null) {
        ToolRegistry tools = getServer().getServicesManager().load(ToolRegistry.class);
        tools.register(new CoinsTool(economy));
    }
}
```

Keep the Craftwire classes behind that check (in a separate class), so the plugin still loads on servers without
Craftwire. The tools are removed when your plugin is disabled.

## Fabric

Declare a `craftwire` entrypoint in `fabric.mod.json`:

```json
"entrypoints": { "craftwire": ["com.example.mymod.MyCraftwireTools"] }
```

```java
public final class MyCraftwireTools implements CraftwireEntrypoint {
    @Override public void registerTools(ToolRegistry registry) {
        registry.register(new SelectionTool());
    }
}
```

Craftwire calls it once the client has started. Without Craftwire installed, the entrypoint is never loaded.

## What the AI sees

- The tools are listed with your description and schema, plus an optional `instance` argument. The AI client is
  told the tool list changed, so new tools show up without a restart.
- `list_instances` shows which instance has which tools. If several servers have the same tool, the AI picks one
  with `instance`.
- A tool can never replace a built-in Craftwire tool. If it has the same name, it is ignored.
- Scenarios can call your tools like any other tool:
  `{"tool": "myshop_coins", "args": {"player": "Buyer"}, "expect": {"path": "coins", "equals": 30}}`.
