package com.payment.gateway.controller;

import com.payment.gateway.controller.DTO.AttachPaymentMethodRequest;
import com.payment.gateway.controller.DTO.CreatePaymentIntentRequest;
import com.payment.gateway.controller.DTO.PaymentIntentResponse;
import com.payment.gateway.entity.PaymentIntent;
import com.payment.gateway.service.PaymentIntentService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RequiredArgsConstructor
@RestController
@RequestMapping("/payment-intents")
public class PaymentIntentController {

    /*
    Step by Step when creating Payment Intent:
    - createPaymentIntent (idempotency)
        | ---- createPaymentMethod
    - attachPaymentMethod (idempotency)
    - confirmPaymentIntent (idempotency)
     */

    private final PaymentIntentService service;

    /*
    TODO: review if its valid always return the same status (201 Created)
        even when the resource already exist (?)
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PaymentIntentResponse createPaymentIntent(
            @RequestBody @Valid CreatePaymentIntentRequest request,
            @NotBlank @RequestHeader(name = "Idempotency-Key") String idempotencyKey
    ) {
        PaymentIntent intent = service.create(request, idempotencyKey);
        return toResponse(intent);
    }

    @PostMapping("/{id}/payment-method") // id reference to payment intent id
    @ResponseStatus(HttpStatus.OK)
    public PaymentIntentResponse attachPaymentMethod(
            @PathVariable UUID id,
            @RequestBody @Valid AttachPaymentMethodRequest request
    ) {
        PaymentIntent intent = service.attachPaymentMethod(id, request);
        return toResponse(intent);
    }

    @PostMapping("/{id}/confirm")
    @ResponseStatus(HttpStatus.OK)
    public PaymentIntentResponse confirmPaymentIntent(@PathVariable UUID id) {
        PaymentIntent intent = service.confirmPaymentIntent(id);

        return toResponse(intent);
    }

    private static PaymentIntentResponse toResponse(PaymentIntent intent) {
        return PaymentIntentResponse.builder()
                .id(intent.getId())
                .amount(intent.getAmount())
                .currency(intent.getCurrency())
                .status(intent.getStatus())
                .description(intent.getDescription())
                .createdAt(intent.getCreatedAt())
                .build();
    }
}
