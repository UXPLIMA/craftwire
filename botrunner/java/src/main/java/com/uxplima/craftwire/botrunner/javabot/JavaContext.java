package com.uxplima.craftwire.botrunner.javabot;

import com.uxplima.craftwire.botrunner.bot.RunnerServices;
import com.uxplima.craftwire.botrunner.javabot.world.JavaBlocks;
import io.netty.channel.EventLoopGroup;
import java.util.concurrent.Executor;

/**
 * What every Java bot of one version shares: the runner's services, the version's block table and packet compat,
 * the Netty event loops, an executor for blocking work (session joins, pack downloads) and the protocol number.
 */
public record JavaContext(RunnerServices services, JavaBlocks blocks, JavaCompat compat, EventLoopGroup group, Executor blocking, int protocol) {}
