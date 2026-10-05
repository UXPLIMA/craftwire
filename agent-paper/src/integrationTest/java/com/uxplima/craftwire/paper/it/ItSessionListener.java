package com.uxplima.craftwire.paper.it;

import org.junit.platform.launcher.LauncherSession;
import org.junit.platform.launcher.LauncherSessionListener;

/** Stops the shared Paper server when the test run ends (the JVM shutdown hook is only a backup). */
public final class ItSessionListener implements LauncherSessionListener {
    @Override
    public void launcherSessionClosed(LauncherSession session) {
        ItEnv.shutdown();
    }
}
