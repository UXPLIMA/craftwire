package com.uxplima.craftwire.bot.move;

/**
 * One tick of movement keys, as a player presses them: facing yaw, forward amount (1 walking, 0.3 sneaking, 0 none),
 * jump and sprint. Each bot kind turns this into its own movement: the server player's input fields, our client
 * physics, or Bedrock's PlayerAuthInput.
 */
public record MoveInput(float yaw, float forward, boolean jump, boolean sprint) {
    public static MoveInput stop(float yaw) {
        return new MoveInput(yaw, 0f, false, false);
    }
}
