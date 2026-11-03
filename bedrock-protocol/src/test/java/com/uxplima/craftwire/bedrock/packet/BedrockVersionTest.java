package com.uxplima.craftwire.bedrock.packet;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Which version a bot speaks to a server, from the protocol its pong names. */
class BedrockVersionTest {
    @Test
    void aServerOnAVersionTheBotHasGetsThatVersion() {
        assertEquals(Optional.of(BedrockVersion.V1_26_50), BedrockVersion.forServer(2193));
        assertEquals(Optional.of(BedrockVersion.V1_26_60), BedrockVersion.forServer(2225));
    }

    /** Geyser names its newest protocol in the pong and still accepts older clients. */
    @Test
    void aServerAheadOfTheBotGetsTheNewestTheBotHas() {
        assertEquals(Optional.of(BedrockVersion.LATEST), BedrockVersion.forServer(2300));
    }

    @Test
    void aServerBetweenTwoVersionsGetsTheOlderOne() {
        assertEquals(Optional.of(BedrockVersion.V1_26_50), BedrockVersion.forServer(2210));
    }

    @Test
    void aServerOlderThanEveryVersionIsRefused() {
        assertEquals(Optional.empty(), BedrockVersion.forServer(2100));
    }
}
