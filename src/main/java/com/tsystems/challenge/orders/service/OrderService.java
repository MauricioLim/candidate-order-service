package com.tsystems.challenge.orders.service;

import com.tsystems.challenge.orders.domain.Order;
import com.tsystems.challenge.orders.domain.OrderStatus;
import com.tsystems.challenge.orders.dto.CreateOrderRequest;
import com.tsystems.challenge.orders.dto.PriceQuoteResponse;
import com.tsystems.challenge.orders.repository.OrderRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class OrderService {
    private final OrderRepository orderRepository;
    private final LocalCatalogPriceService priceService;
    private final PricingClient pricingClient;
    private final Clock clock;

    @Autowired
    public OrderService(OrderRepository orderRepository, LocalCatalogPriceService priceService, PricingClient pricingClient) {
        this(orderRepository, priceService, pricingClient, Clock.systemUTC());
    }

    OrderService(OrderRepository orderRepository, LocalCatalogPriceService priceService, PricingClient pricingClient, Clock clock) {
        this.orderRepository = orderRepository;
        this.priceService = priceService;
        this.pricingClient = pricingClient;
        this.clock = clock;
    }

    public Order create(CreateOrderRequest request) {
        Order order = new Order(
                UUID.randomUUID(),
                request.customerId(),
                request.productId(),
                request.quantity(),
                request.country(),
                request.currency(),
                null,
                null,
                OrderStatus.PENDING_PRICING,
                Instant.now(clock),
                0,
                null,
                Instant.now(clock)
        );
        order = orderRepository.save(order);

        return attemptPricing(order);
    }


    public Order retryPricing(UUID id) {
        Order order = get(id);
        if (order.status() == OrderStatus.CONFIRMED) {
            return order;
        }
        return attemptPricing(order);
    }


    public List<Order> retryPendingOrders() {
        return orderRepository.findAll().stream()
                .filter(o -> o.status() == OrderStatus.PENDING_PRICING)
                .map(this::attemptPricing)
                .toList();
    }


    private Order attemptPricing(Order order) {
        try {
            PriceQuoteResponse quote = pricingClient.getPrice(order.productId(), order.country(), order.currency());

            BigDecimal unitPrice = new BigDecimal(quote.amount());
            BigDecimal totalPrice = unitPrice.multiply(BigDecimal.valueOf(order.quantity()));

            Order confirmed = new Order(
                    order.id(),
                    order.customerId(),
                    order.productId(),
                    order.quantity(),
                    order.country(),
                    order.currency(),
                    unitPrice,
                    totalPrice,
                    OrderStatus.CONFIRMED,
                    order.createdAt(),
                    order.pricingAttempts() + 1,
                    null,
                    Instant.now(clock)
            );
            return orderRepository.save(confirmed);

        } catch (PricingProductNotFoundException | PricingBadRequestException permanent) {
            Order failed = withPricingOutcome(order, OrderStatus.PRICING_FAILED, permanent.getMessage());
            return orderRepository.save(failed);

        } catch (PricingUnavailableException transient_) {
            Order stillPending = withPricingOutcome(order, OrderStatus.PENDING_PRICING, transient_.getMessage());
            return orderRepository.save(stillPending);
        }
    }

    private Order withPricingOutcome(Order order, OrderStatus status, String failureReason) {
        return new Order(
                order.id(),
                order.customerId(),
                order.productId(),
                order.quantity(),
                order.country(),
                order.currency(),
                order.unitPrice(),
                order.totalPrice(),
                status,
                order.createdAt(),
                order.pricingAttempts() + 1,
                failureReason,
                Instant.now(clock)
        );
    }

    public Order get(UUID id) {
        return orderRepository.findById(id)
                .orElseThrow(() -> new OrderNotFoundException(id));
    }

    public List<Order> list() {
        return orderRepository.findAll();
    }
}
