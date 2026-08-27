package com.tsystems.challenge.orders.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.tsystems.challenge.orders.dto.PriceQuoteResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

@Component
public class PricingClient {

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build();

    private final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule());

    private final String baseUrl;

    public PricingClient(@Value("${pricing.api.url:http://localhost:8090}") String baseUrl) {
        this.baseUrl = baseUrl;
    }


    @Retryable(
            retryFor = PricingUnavailableException.class,
            maxAttempts = 3,
            backoff = @Backoff(delay = 200, multiplier = 2)
    )
    public PriceQuoteResponse getPrice(String productId, String country, String currency) {
        String uri = baseUrl + "/v1/prices/" + productId
                + "?country=" + country + "&currency=" + currency;

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(uri))
                .timeout(Duration.ofSeconds(3))
                .GET()
                .build();

        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new PricingUnavailableException("Pricing API call failed for product '" + productId + "'", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PricingUnavailableException("Pricing API call was interrupted for product '" + productId + "'", e);
        }

        int status = response.statusCode();

        if (status == 200) {
            try {
                return mapper.readValue(response.body(), PriceQuoteResponse.class);
            } catch (IOException e) {
                throw new PricingUnavailableException("Pricing API returned an unparseable 200 body for product '" + productId + "'", e);
            }
        }

        if (status == 404) {
            throw new PricingProductNotFoundException(productId);
        }

        if (status == 400) {
            throw new PricingBadRequestException("Pricing API rejected the request for product '" + productId + "': " + response.body());
        }

        if (status >= 500 && status < 600) {
            throw new PricingUnavailableException("Pricing API returned " + status + " for product '" + productId + "'");
        }

        throw new PricingUnavailableException("Pricing API returned unexpected status " + status + " for product '" + productId + "'");
    }
}
