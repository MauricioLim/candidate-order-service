package com.tsystems.challenge.orders.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.tsystems.challenge.orders.dto.PriceQuoteResponse;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

public class Princing {

    // http://localhost:8090/v1/prices/SKU-1001?country=DE&currency=EUR
    public String pricingCheck(String id, String country, String currency) throws JsonProcessingException {
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:8090/v1/prices/"+ id +"?country="+country+"&currency=" + currency))
                .build();
        HttpResponse<String> response = null;
        try {
            response = client.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new RuntimeException(e);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }

        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        PriceQuoteResponse quote = mapper.readValue(response.body(), PriceQuoteResponse.class);
        String amount = quote.amount();


        return amount;
    }

}
