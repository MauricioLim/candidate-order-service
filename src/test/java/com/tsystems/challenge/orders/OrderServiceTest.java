package com.tsystems.challenge.orders;

import com.tsystems.challenge.orders.domain.Order;
import com.tsystems.challenge.orders.domain.OrderStatus;
import com.tsystems.challenge.orders.dto.CreateOrderRequest;
import com.tsystems.challenge.orders.repository.InMemoryOrderRepository;
import com.tsystems.challenge.orders.service.LocalCatalogPriceService;
import com.tsystems.challenge.orders.service.OrderService;
import com.tsystems.challenge.orders.service.PricingClient;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OrderServiceTest {

    @Test
    void createsAndConfirmsAnOrderUsingTheLocalCatalog() {
        // NOTE: this still hits the real Pricing API over HTTP (localhost:8090),
        // it was not mocked before either. Requires the Pricing API Docker
        // container running with SKU-1001 available.
        OrderService service = new OrderService(
                new InMemoryOrderRepository(),
                new LocalCatalogPriceService(),
                new PricingClient()
        );

        Order order = service.create(new CreateOrderRequest(
                "customer-42",
                "SKU-1001",
                2,
                "DE",
                "EUR"
        ));

        assertThat(order.status()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(order.unitPrice()).isEqualByComparingTo("19.99");
        assertThat(order.totalPrice()).isEqualByComparingTo("39.98");
    }
}
