package com.uxplima.craftwire.botrunner.world;

/** One dimension of one server, as bots reach it: "host:port" plus the dimension id (minecraft:overworld). */
public record WorldKey(String server, String dimension) {}
