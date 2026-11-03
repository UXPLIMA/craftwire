package com.uxplima.craftwire.botrunner.bedrock;

import static org.junit.jupiter.api.Assertions.*;

import com.uxplima.craftwire.core.AgentError;
import org.junit.jupiter.api.Test;

/** Join failures as agent errors: the code decides which hint the agent acts on, so a sign-in refusal must not read as a ban. */
class BedrockJoinTest {
    @Test
    void geyserXboxKickInAnyLanguageAsksForAnAccount() {
        // Geyser 2.12.1's kick on a Turkish install, as a Bedrock bot received it from a server with validate-bedrock-login.
        AgentError e = BedrockJoin.failure("kicked: Bu sunucuya katılmak için lütfen Xbox'ta oturum açın.");
        assertEquals("ONLINE_MODE", e.code());
        assertTrue(e.getMessage().endsWith("Bu sunucuya katılmak için lütfen Xbox'ta oturum açın."), e.getMessage());
        assertTrue(e.hint().contains("sign_in"), e.hint());
    }

    @Test
    void silenceAfterTheLoginAsksForAnAccount() {
        assertEquals("ONLINE_MODE", BedrockJoin.failure(BedrockSession.SILENT_AFTER_LOGIN).code());
    }

    @Test
    void otherKicksStayKicks() {
        AgentError e = BedrockJoin.failure("kicked: You are banned from this server");
        assertEquals("JOIN_KICKED", e.code());
        assertTrue(e.getMessage().endsWith("You are banned from this server"), e.getMessage());
    }

    @Test
    void aPlainTimeoutIsAJoinFailure() {
        assertEquals("JOIN_FAILED", BedrockJoin.failure("the server stopped answering (timed out)").code());
    }
}
