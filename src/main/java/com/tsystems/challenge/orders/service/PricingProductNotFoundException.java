package com.tsystems.challenge.orders.service;

public class PricingProductNotFoundException extends PricingException {
    public PricingProductNotFoundException(String productId) {
        super("Pricing API has no price for product '" + productId + "'");
    }
}
