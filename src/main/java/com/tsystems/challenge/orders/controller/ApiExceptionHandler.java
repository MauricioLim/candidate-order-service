package com.tsystems.challenge.orders.controller;

import com.tsystems.challenge.orders.service.OrderNotFoundException;
import com.tsystems.challenge.orders.service.PricingBadRequestException;
import com.tsystems.challenge.orders.service.PricingProductNotFoundException;
import com.tsystems.challenge.orders.service.PricingUnavailableException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(OrderNotFoundException.class)
    ProblemDetail handleNotFound(OrderNotFoundException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail handleBadRequest(IllegalArgumentException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
    }


    @ExceptionHandler(PricingProductNotFoundException.class)
    ProblemDetail handlePricingProductNotFound(PricingProductNotFoundException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
    }


    @ExceptionHandler(PricingBadRequestException.class)
    ProblemDetail handlePricingBadRequest(PricingBadRequestException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_GATEWAY, ex.getMessage());
    }


    @ExceptionHandler(PricingUnavailableException.class)
    ProblemDetail handlePricingUnavailable(PricingUnavailableException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage());
    }
}
