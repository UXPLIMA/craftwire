package com.uxplima.craftwire.paper.bot;

import com.uxplima.craftwire.core.AgentError;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/** Minecraft player names: 3-16 letters, digits or underscores. */
public final class BotNames {
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_]{3,16}");
    private static final Pattern PREFIX = Pattern.compile("[A-Za-z0-9_]{1,13}");

    private BotNames() {}

    public static String validate(String name) {
        if (!NAME.matcher(name).matches()) {
            throw new AgentError("INVALID_PARAMS", "Bot names are 3-16 letters, digits or _: " + name, "Use names like Bot1 or Tester_2.");
        }
        return name;
    }

    /** `count` names prefix1, prefix2, … that `taken` does not report as in use. */
    public static List<String> allocate(String prefix, int count, Predicate<String> taken) {
        if (!PREFIX.matcher(prefix).matches()) {
            throw new AgentError("INVALID_PARAMS", "namePrefix must be 1-13 letters, digits or _", "Use a short prefix like Bot.");
        }
        List<String> out = new ArrayList<>();
        for (int i = 1; out.size() < count; i++) {
            String name = prefix + i;
            if (name.length() > 16) {
                throw new AgentError("INVALID_PARAMS", "No free names left for prefix " + prefix, "Use a shorter namePrefix, or remove some bots.");
            }
            if (name.length() >= 3 && !taken.test(name)) out.add(name);
        }
        return out;
    }
}
