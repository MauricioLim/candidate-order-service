package com.tsystems.challenge.orders.service;

public class PricingUnavailableException extends PricingException {
    public PricingUnavailableException(String message) {
        super(message);
    }

    public PricingUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
