package com.tsystems.challenge.orders.service;

import com.sun.net.httpserver.HttpServer;
import com.tsystems.challenge.orders.dto.PriceQuoteResponse;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.retry.annotation.EnableRetry;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;


@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = PricingClientRetryTest.RetryTestConfig.class)
class PricingClientRetryTest {

    private static HttpServer server;
    private static final AtomicInteger requestCount = new AtomicInteger(0);


    @FunctionalInterface
    private interface ResponseScript {
        StatusAndBody respond(int attemptNumber);
    }

    private record StatusAndBody(int status, byte[] body) {
        static StatusAndBody of(int status, String body) {
            return new StatusAndBody(status, body.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static final String SUCCESS_BODY = """
            {
              "quoteId": "quote-1",
              "productId": "SKU-1001",
              "country": "DE",
              "amount": "19.99",
              "currency": "EUR",
              "validUntil": "2026-01-01T12:00:00Z"
            }
            """;

    private static volatile ResponseScript script = attempt -> StatusAndBody.of(200, SUCCESS_BODY);

    @Configuration
    @EnableRetry
    static class RetryTestConfig {
        @Bean
        PricingClient pricingClient(@Value("${pricing.api.url}") String baseUrl) {
            return new PricingClient(baseUrl);
        }
    }

    @DynamicPropertySource
    static void registerPricingApiUrl(DynamicPropertyRegistry registry) throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/v1/prices/", exchange -> {
            int attempt = requestCount.incrementAndGet();
            StatusAndBody response = script.respond(attempt);

            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(response.status(), response.body().length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response.body());
            }
        });
        server.start();

        int port = server.getAddress().getPort();
        registry.add("pricing.api.url", () -> "http://localhost:" + port);
    }

    @Autowired
    private PricingClient pricingClient;

    @AfterEach
    void resetState() {
        requestCount.set(0);
        script = attempt -> StatusAndBody.of(200, SUCCESS_BODY);
    }

    @AfterAll
    static void stopServer() {
        server.stop(0);
    }

    @Test
    void getPrice_retriesAndSucceeds_whenFirstAttemptsAreTransientFailures() {

        script = attempt -> attempt <= 2
                ? StatusAndBody.of(503, "")
                : StatusAndBody.of(200, SUCCESS_BODY);

        PriceQuoteResponse quote = pricingClient.getPrice("SKU-1001", "DE", "EUR");

        assertThat(quote.quoteId()).isEqualTo("quote-1");
        assertThat(requestCount.get()).isEqualTo(3);
    }

    @Test
    void getPrice_givesUpAfterMaxAttempts_whenPricingApiKeepsFailing() {

        script = attempt -> StatusAndBody.of(503, "");

        assertThatThrownBy(() -> pricingClient.getPrice("SKU-1001", "DE", "EUR"))
                .isInstanceOf(PricingUnavailableException.class);

        assertThat(requestCount.get()).isEqualTo(3);
    }

    @Test
    void getPrice_doesNotRetry_onPermanentFailure() {

        script = attempt -> StatusAndBody.of(404, "");

        assertThatThrownBy(() -> pricingClient.getPrice("SKU-UNKNOWN", "DE", "EUR"))
                .isInstanceOf(PricingProductNotFoundException.class);

        assertThat(requestCount.get()).isEqualTo(1);
    }
}