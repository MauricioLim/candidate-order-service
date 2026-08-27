package com.tsystems.challenge.orders.service;

import com.tsystems.challenge.orders.domain.Order;
import com.tsystems.challenge.orders.domain.OrderStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.*;


class PricingRetrySchedulerTest {

    private OrderService orderService;
    private PricingRetryScheduler scheduler;

    @BeforeEach
    void setUp() {
        orderService = mock(OrderService.class);
        scheduler = new PricingRetryScheduler(orderService);
    }

    @Test
    void retryPendingOrders_delegatesToOrderService() {
        when(orderService.retryPendingOrders()).thenReturn(List.of());

        scheduler.retryPendingOrders();

        verify(orderService, times(1)).retryPendingOrders();
    }

    @Test
    void retryPendingOrders_doesNotThrow_whenOrdersWereRetried() {
        Order retried = new Order(
                UUID.randomUUID(), "customer-1", "SKU-1002", 1, "DE", "EUR",
                null, null, OrderStatus.CONFIRMED, Instant.now(), 2, null, Instant.now()
        );
        when(orderService.retryPendingOrders()).thenReturn(List.of(retried));

        scheduler.retryPendingOrders();

        verify(orderService, times(1)).retryPendingOrders();
    }
}
