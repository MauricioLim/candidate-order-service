package com.tsystems.challenge.orders.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.tsystems.challenge.orders.domain.Order;
import com.tsystems.challenge.orders.domain.OrderStatus;
import com.tsystems.challenge.orders.dto.CreateOrderRequest;
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
    private final Clock clock;

    @Autowired
    public OrderService(OrderRepository orderRepository, LocalCatalogPriceService priceService) {
        this(orderRepository, priceService, Clock.systemUTC());
    }

    OrderService(OrderRepository orderRepository, LocalCatalogPriceService priceService, Clock clock) {
        this.orderRepository = orderRepository;
        this.priceService = priceService;
        this.clock = clock;
    }

    public Order create(CreateOrderRequest request) throws JsonProcessingException {
        Princing p = new Princing();
        String json = p.pricingCheck(request.productId(), request.country(), request.currency());

        BigDecimal unitPrice = new BigDecimal(json);
        BigDecimal totalPrice = unitPrice.multiply(BigDecimal.valueOf(request.quantity()));


        Order order = new Order(
                UUID.randomUUID(),
                request.customerId(),
                request.productId(),
                request.quantity(),
                request.country(),
                request.currency(),
                unitPrice,
                totalPrice,
                OrderStatus.PENDING,
                Instant.now(clock),
                1,
                "teste",
                Instant.now(clock)
        );

        return orderRepository.save(order);
    }

    public Order get(UUID id) {
        return orderRepository.findById(id)
                .orElseThrow(() -> new OrderNotFoundException(id));
    }

    public List<Order> list() {
        return orderRepository.findAll();
    }
}
