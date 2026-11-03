package com.uxplima.craftwire.botrunner;

import com.uxplima.craftwire.bot.model.BotKind;
import com.uxplima.craftwire.botrunner.bot.BotDriver;
import com.uxplima.craftwire.botrunner.bot.RunnerBots;
import com.uxplima.craftwire.botrunner.bot.RunnerServices;
import com.uxplima.craftwire.botrunner.loop.RunnerLoop;
import com.uxplima.craftwire.core.HubConfig;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Starts a bot runner: java -jar craftwire-botrunner.jar [--name runner-1] [--kinds java,bedrock] [--max-bots 1000]
 * [--home ~/.craftwire]. It connects to the hub found in the home folder (hub.json, as the agents do) and runs until
 * it is stopped.
 */
public final class Main {
    private static final Logger LOG = LogManager.getLogger("craftwire-botrunner");

    private Main() {}

    public static void main(String[] args) throws InterruptedException {
        Options o = Options.parse(args);
        RunnerLoop loop = new RunnerLoop();
        RunnerServices services = RunnerServices.create(loop, o.home());
        List<BotDriver> drivers = Drivers.create(o.kinds(), services);
        RunnerBots bots = new RunnerBots(services, drivers, o.maxBots());
        RunnerAgent agent = new RunnerAgent(bots, o.name(), o.home());
        CountDownLatch stopped = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            LOG.info("Stopping the bot runner");
            agent.close();
            bots.close();
            loop.close();
            stopped.countDown();
        }, "craftwire-botrunner-stop"));
        loop.start();
        agent.start();
        LOG.info("Bot runner {} started ({} bots at most, kinds {})", o.name(), o.maxBots(), o.kinds());
        stopped.await();
    }

    /** Command-line options; anything unknown is an error, so a typo never silently runs the defaults. */
    record Options(String name, Set<BotKind> kinds, int maxBots, Path home) {
        static Options parse(String[] args) {
            String name = "botrunner";
            Set<BotKind> kinds = EnumSet.of(BotKind.JAVA, BotKind.BEDROCK);
            int maxBots = 1000;
            Path home = HubConfig.defaultHome();
            for (int i = 0; i < args.length; i++) {
                String value = i + 1 < args.length ? args[i + 1] : null;
                if (value == null) throw new IllegalArgumentException(args[i] + " needs a value");
                switch (args[i]) {
                    case "--name" -> name = value;
                    case "--kinds" -> kinds = kinds(value);
                    case "--max-bots" -> maxBots = Math.max(1, Integer.parseInt(value));
                    case "--home" -> home = Path.of(value);
                    default -> throw new IllegalArgumentException("Unknown option " + args[i]);
                }
                i++;
            }
            return new Options(name, kinds, maxBots, home);
        }

        private static Set<BotKind> kinds(String list) {
            Set<BotKind> out = EnumSet.noneOf(BotKind.class);
            for (String k : list.split(",")) {
                BotKind kind = BotKind.parse(k.trim());
                if (kind == BotKind.PLUGIN) throw new IllegalArgumentException("plugin bots run inside a Paper server");
                out.add(kind);
            }
            if (out.isEmpty()) throw new IllegalArgumentException("--kinds needs java, bedrock or both");
            return out;
        }
    }
}
