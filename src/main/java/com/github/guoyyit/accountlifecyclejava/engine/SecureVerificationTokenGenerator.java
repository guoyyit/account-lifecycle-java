package com.github.guoyyit.accountlifecyclejava.engine;

import java.security.SecureRandom;
import java.util.HexFormat;

public class SecureVerificationTokenGenerator implements VerificationTokenGenerator {

    private static final int TOKEN_BYTES = 32;

    private final SecureRandom secureRandom = new SecureRandom();

    @Override
    public String generate() {
        byte[] bytes = new byte[TOKEN_BYTES];
        secureRandom.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }
}
