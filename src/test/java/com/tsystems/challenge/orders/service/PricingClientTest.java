package com.tsystems.challenge.orders.service;

import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import com.tsystems.challenge.orders.dto.PriceQuoteResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;


class PricingClientTest {

    private HttpServer server;
    private PricingClient pricingClient;


    private volatile HttpHandler handler;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/v1/prices/", exchange -> handler.handle(exchange));
        server.start();

        int port = server.getAddress().getPort();
        pricingClient = new PricingClient("http://localhost:" + port);
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private void respondWith(int status, String body) {
        handler = exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        };
    }

    @Test
    void getPrice_returnsQuote_onHttp200() {
        respondWith(200, """
                {
                  "quoteId": "quote-1",
                  "productId": "SKU-1001",
                  "country": "DE",
                  "amount": "19.99",
                  "currency": "EUR",
                  "validUntil": "2026-01-01T12:00:00Z"
                }
                """);

        PriceQuoteResponse quote = pricingClient.getPrice("SKU-1001", "DE", "EUR");

        assertThat(quote.quoteId()).isEqualTo("quote-1");
        assertThat(quote.productId()).isEqualTo("SKU-1001");
        assertThat(quote.amount()).isEqualTo("19.99");
        assertThat(quote.currency()).isEqualTo("EUR");
    }

    @Test
    void getPrice_throwsProductNotFound_onHttp404() {
        respondWith(404, "");

        assertThatThrownBy(() -> pricingClient.getPrice("SKU-UNKNOWN", "DE", "EUR"))
                .isInstanceOf(PricingProductNotFoundException.class)
                .hasMessageContaining("SKU-UNKNOWN");
    }

    @Test
    void getPrice_throwsBadRequest_onHttp400() {
        respondWith(400, "{\"error\": \"invalid currency\"}");

        assertThatThrownBy(() -> pricingClient.getPrice("SKU-1001", "DE", "XXX"))
                .isInstanceOf(PricingBadRequestException.class)
                .hasMessageContaining("SKU-1001");
    }

    @Test
    void getPrice_throwsUnavailable_onHttp503() {
        respondWith(503, "");

        assertThatThrownBy(() -> pricingClient.getPrice("SKU-1001", "DE", "EUR"))
                .isInstanceOf(PricingUnavailableException.class)
                .hasMessageContaining("503");
    }

    @Test
    void getPrice_throwsUnavailable_onUnparseableBody() {

        respondWith(200, "not-json-at-all");

        assertThatThrownBy(() -> pricingClient.getPrice("SKU-1001", "DE", "EUR"))
                .isInstanceOf(PricingUnavailableException.class);
    }

    @Test
    void getPrice_throwsUnavailable_onHttp429() {

        respondWith(429, "");

        assertThatThrownBy(() -> pricingClient.getPrice("SKU-1001", "DE", "EUR"))
                .isInstanceOf(PricingUnavailableException.class)
                .hasMessageContaining("429");
    }
}
