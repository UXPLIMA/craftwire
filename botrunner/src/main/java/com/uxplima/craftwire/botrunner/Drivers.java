package com.uxplima.craftwire.botrunner;

import com.uxplima.craftwire.bot.model.BotKind;
import com.uxplima.craftwire.botrunner.bot.BotDriver;
import com.uxplima.craftwire.botrunner.bot.RunnerServices;
import com.uxplima.craftwire.botrunner.java.JavaDriver;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** The bot kinds this runner was started with (--kinds), as drivers. */
final class Drivers {
    private Drivers() {}

    static List<BotDriver> create(Set<BotKind> kinds, RunnerServices services) {
        List<BotDriver> out = new ArrayList<>();
        if (kinds.contains(BotKind.JAVA)) out.add(new JavaDriver(services));
        return out;
    }
}
