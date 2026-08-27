package com.tsystems.challenge.orders.service;

import com.tsystems.challenge.orders.domain.Order;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;


@Component
public class PricingRetryScheduler {

    private static final Logger log = LoggerFactory.getLogger(PricingRetryScheduler.class);

    private final OrderService orderService;

    public PricingRetryScheduler(OrderService orderService) {
        this.orderService = orderService;
    }

    @Scheduled(fixedDelayString = "${pricing.retry.scheduler.fixed-delay-ms:30000}")
    public void retryPendingOrders() {
        List<Order> retried = orderService.retryPendingOrders();
        if (!retried.isEmpty()) {
            log.info("Pricing retry scheduler re-attempted {} pending order(s)", retried.size());
        }
    }
}
