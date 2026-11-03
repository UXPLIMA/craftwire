package com.uxplima.craftwire.bedrock.codec;

/** Bytes that do not decode as the Bedrock protocol says they should: a bad length, varint or tag. */
public final class BedrockCodecException extends RuntimeException {
    public BedrockCodecException(String message) {
        super(message);
    }

    public BedrockCodecException(String message, Throwable cause) {
        super(message, cause);
    }
}
