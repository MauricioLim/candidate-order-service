package com.tsystems.challenge.orders.service;

import com.tsystems.challenge.orders.domain.Order;
import com.tsystems.challenge.orders.domain.OrderStatus;
import com.tsystems.challenge.orders.dto.CreateOrderRequest;
import com.tsystems.challenge.orders.dto.PriceQuoteResponse;
import com.tsystems.challenge.orders.repository.OrderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;


class OrderServiceTest {

    private OrderRepository repository;
    private PricingClient pricingClient;
    private OrderService service;
    private final Clock fixedClock = Clock.fixed(Instant.parse("2026-01-01T10:00:00Z"), ZoneOffset.UTC);

    @BeforeEach
    void setUp() {
        repository = mock(OrderRepository.class);
        pricingClient = mock(PricingClient.class);


        when(repository.save(any(Order.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service = new OrderService(repository, pricingClient, fixedClock);
    }

    private CreateOrderRequest sampleRequest() {
        return new CreateOrderRequest("customer-42", "SKU-1001", 2, "DE", "EUR");
    }

    @Test
    void create_confirmsOrder_whenPricingSucceeds() {
        PriceQuoteResponse quote = new PriceQuoteResponse(
                "quote-1", "SKU-1001", "DE", "19.99", "EUR",
                OffsetDateTime.now().plusHours(1)
        );
        when(pricingClient.getPrice("SKU-1001", "DE", "EUR")).thenReturn(quote);

        Order order = service.create(sampleRequest());

        assertThat(order.status()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(order.unitPrice()).isEqualByComparingTo("19.99");
        assertThat(order.totalPrice()).isEqualByComparingTo("39.98");
        assertThat(order.pricingAttempts()).isEqualTo(1);
        assertThat(order.failureReason()).isNull();
    }

    @Test
    void create_marksOrderAsPricingFailed_whenProductNotFound() {
        when(pricingClient.getPrice(anyString(), anyString(), anyString()))
                .thenThrow(new PricingProductNotFoundException("SKU-1001"));

        Order order = service.create(sampleRequest());

        assertThat(order.status()).isEqualTo(OrderStatus.PRICING_FAILED);
        assertThat(order.unitPrice()).isNull();
        assertThat(order.pricingAttempts()).isEqualTo(1);
        assertThat(order.failureReason()).contains("SKU-1001");
    }

    @Test
    void create_keepsOrderPendingWithReason_whenPricingIsTemporarilyUnavailable() {
        when(pricingClient.getPrice(anyString(), anyString(), anyString()))
                .thenThrow(new PricingUnavailableException("Pricing API returned 503"));

        Order order = service.create(sampleRequest());

        assertThat(order.status()).isEqualTo(OrderStatus.PENDING_PRICING);
        assertThat(order.pricingAttempts()).isEqualTo(1);
        assertThat(order.failureReason()).isEqualTo("Pricing API returned 503");
    }

    @Test
    void create_persistsOrderBeforeCallingPricingClient() {

        when(pricingClient.getPrice(anyString(), anyString(), anyString()))
                .thenThrow(new PricingUnavailableException("timeout"));

        service.create(sampleRequest());

        InOrder callOrder = inOrder(repository, pricingClient);
        callOrder.verify(repository).save(any(Order.class));       // initial PENDING_PRICING save
        callOrder.verify(pricingClient).getPrice(anyString(), anyString(), anyString());
        callOrder.verify(repository).save(any(Order.class));       // outcome save
    }

    @Test
    void retryPricing_doesNotCallPricingClientAgain_whenOrderAlreadyConfirmed() {
        UUID id = UUID.randomUUID();
        Order confirmed = new Order(
                id, "customer-42", "SKU-1001", 2, "DE", "EUR",
                new java.math.BigDecimal("19.99"), new java.math.BigDecimal("39.98"),
                OrderStatus.CONFIRMED, Instant.now(fixedClock), 1, null, Instant.now(fixedClock)
        );
        when(repository.findById(id)).thenReturn(java.util.Optional.of(confirmed));

        Order result = service.retryPricing(id);

        assertThat(result).isEqualTo(confirmed);
        verifyNoInteractions(pricingClient);
    }

    @Test
    void retryPendingOrders_onlyRetriesOrdersStillPendingPricing() {
        UUID pendingId = UUID.randomUUID();
        UUID confirmedId = UUID.randomUUID();

        Order pending = new Order(
                pendingId, "customer-1", "SKU-1002", 1, "DE", "EUR",
                null, null, OrderStatus.PENDING_PRICING, Instant.now(fixedClock), 1, "timeout", Instant.now(fixedClock)
        );
        Order confirmed = new Order(
                confirmedId, "customer-2", "SKU-1003", 1, "DE", "EUR",
                new java.math.BigDecimal("7.25"), new java.math.BigDecimal("7.25"),
                OrderStatus.CONFIRMED, Instant.now(fixedClock), 1, null, Instant.now(fixedClock)
        );
        when(repository.findAll()).thenReturn(List.of(pending, confirmed));
        when(pricingClient.getPrice("SKU-1002", "DE", "EUR"))
                .thenReturn(new PriceQuoteResponse("q2", "SKU-1002", "DE", "29.50", "EUR", OffsetDateTime.now().plusHours(1)));

        List<Order> result = service.retryPendingOrders();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).id()).isEqualTo(pendingId);
        assertThat(result.get(0).status()).isEqualTo(OrderStatus.CONFIRMED);
        verify(pricingClient, times(1)).getPrice(anyString(), anyString(), anyString());
    }
}
