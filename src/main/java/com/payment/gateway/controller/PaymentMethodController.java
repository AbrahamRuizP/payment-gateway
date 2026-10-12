package com.payment.gateway.controller;

import com.payment.gateway.controller.DTO.CreatePaymentMethodRequest;
import com.payment.gateway.controller.DTO.PaymentMethodResponse;
import com.payment.gateway.entity.PaymentMethod;
import com.payment.gateway.service.PaymentMethodService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RequiredArgsConstructor
@RestController
@RequestMapping("/payment-methods")
public class PaymentMethodController {

    private final PaymentMethodService service;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PaymentMethodResponse createPaymentMethod(
            @RequestBody @Valid CreatePaymentMethodRequest request
    ) {
        PaymentMethod paymentMethod = service.create(request);
        return toResponse(paymentMethod);
    }

    @GetMapping("/{id}")
    @ResponseStatus(HttpStatus.OK)
    public PaymentMethodResponse getPaymentMethod(@PathVariable UUID id) {
        PaymentMethod paymentMethod = service.findById(id);
        return toResponse(paymentMethod);
    }

    private static PaymentMethodResponse toResponse(PaymentMethod method) {
        return PaymentMethodResponse.builder()
                .id(method.getId())
                .provider(method.getProvider())
                .type(method.getType())
                .createdAt(method.getCreatedAt())
                .build();
    }
}
