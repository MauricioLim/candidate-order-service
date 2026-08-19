package com.tsystems.challenge.orders.dto;

import java.time.OffsetDateTime;

public record PriceQuoteResponse(
        String quoteId,
        String productId,
        String country,
        String amount,
        String currency,
        OffsetDateTime validUntil
) {}