package com.payment.gateway.exception;

public class IdempotencyOperationInProgressException extends RuntimeException {
    public IdempotencyOperationInProgressException(String message) {
        super(message);
    }
}
