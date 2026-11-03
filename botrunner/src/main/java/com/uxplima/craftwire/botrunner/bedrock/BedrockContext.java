package com.uxplima.craftwire.botrunner.bedrock;

import com.uxplima.craftwire.botrunner.bot.RunnerServices;
import io.netty.channel.EventLoopGroup;
import java.util.concurrent.Executor;

/**
 * What every Bedrock bot of the runner shares: the runner services, the block list, the UDP event loops and an
 * executor for blocking work (Xbox sign-in, the first download of the block list).
 */
record BedrockContext(RunnerServices services, BedrockBlocks blocks, EventLoopGroup group, Executor blocking) {}
