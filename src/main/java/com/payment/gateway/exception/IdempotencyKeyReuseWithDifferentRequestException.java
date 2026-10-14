package com.payment.gateway.exception;

public class IdempotencyKeyReuseWithDifferentRequestException extends RuntimeException {
    public IdempotencyKeyReuseWithDifferentRequestException(String message) {
        super(message);
    }
}
