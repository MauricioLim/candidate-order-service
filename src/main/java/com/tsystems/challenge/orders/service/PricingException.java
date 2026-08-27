package com.tsystems.challenge.orders.service;


public abstract class PricingException extends RuntimeException {
    protected PricingException(String message) {
        super(message);
    }

    protected PricingException(String message, Throwable cause) {
        super(message, cause);
    }
}
