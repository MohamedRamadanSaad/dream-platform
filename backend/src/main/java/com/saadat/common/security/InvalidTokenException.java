package com.saadat.common.security;

/** Thrown by {@link JwtService#verify(String)} for any malformed, tampered or expired access token. */
public class InvalidTokenException extends RuntimeException {

    public InvalidTokenException(String message, Throwable cause) {
        super(message, cause);
    }

    public InvalidTokenException(String message) {
        super(message);
    }
}
