package com.uxplima.craftwire.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class VersionOrderTest {
    private static final List<String> SUPPORTED = List.of("26.2", "26.3");

    @Test
    void picksTheExactVersion() {
        assertEquals("26.2", VersionOrder.select("26.2", SUPPORTED));
        assertEquals("26.3", VersionOrder.select("26.3", SUPPORTED));
    }

    @Test
    void patchReleasesUseTheirMinorVersion() {
        assertEquals("26.2", VersionOrder.select("26.2.1", SUPPORTED));
        assertEquals("26.3", VersionOrder.select("26.3.2", SUPPORTED));
    }

    @Test
    void aNewerGameUsesTheNewestCode() {
        assertEquals("26.3", VersionOrder.select("26.4-alpha.26.40.3", SUPPORTED));
    }

    @Test
    void comparesNumericallyNotAsText() {
        assertTrue(VersionOrder.compare("26.10", "26.9") > 0);
        assertEquals(0, VersionOrder.compare("26.3", "26.3.0"));
        assertTrue(VersionOrder.compare("26.3-rc.1", "26.2") > 0);
    }
}
