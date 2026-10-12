package com.payment.gateway.service;

import com.payment.gateway.controller.DTO.AttachPaymentMethodRequest;
import com.payment.gateway.controller.DTO.CreatePaymentIntentRequest;
import com.payment.gateway.entity.Customer;
import com.payment.gateway.entity.Merchant;
import com.payment.gateway.entity.PaymentIntent;
import com.payment.gateway.entity.PaymentMethod;
import com.payment.gateway.entity.enums.MerchantStatus;
import com.payment.gateway.entity.enums.PaymentIntentStatus;
import com.payment.gateway.exception.*;
import com.payment.gateway.repository.CustomerRepository;
import com.payment.gateway.repository.MerchantRepository;
import com.payment.gateway.repository.PaymentIntentRepository;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class PaymentIntentService {

    private final PaymentIntentRepository paymentIntentRepository;
    private final PaymentMethodService paymentMethodService;
    private final CustomerRepository customerRepository;
    private final MerchantRepository merchantRepository;

    public PaymentIntent findById(UUID id) {
        return paymentIntentRepository.findById(id)
                .orElseThrow(() -> new PaymentIntentNotFoundException(id));
    }

    @Transactional
    public PaymentIntent create(CreatePaymentIntentRequest request) {
        if (!customerRepository.existsByIdAndDeletedFalse(request.customerId())) {
            throw new CustomerNotFoundException(request.customerId());

        } else if (!merchantRepository.existsByIdAndStatus(request.merchantId(), MerchantStatus.ACTIVE)) {
            throw new MerchantNotFoundException(request.merchantId());
        }

        PaymentIntent paymentIntent = buildIntent(request);
        return paymentIntentRepository.save(paymentIntent);
    }

    @Transactional
    public PaymentIntent attachPaymentMethod(
            UUID intentId,
            AttachPaymentMethodRequest request
    ) {
        PaymentMethod paymentMethod = paymentMethodService.findById(request.paymentMethodId());
        PaymentIntent paymentIntent = findById(intentId);

        if (!paymentIntent.getCustomer().getId()
                .equals(paymentMethod.getCustomer().getId())) {
            throw new InvalidPaymentMethodException("The payment intent and payment method " +
                    "must belong to the same customer.");
        }

        // to validate payment intent --> payment method relationship
        if (paymentIntent.getPaymentMethod() != null ) {
            throw new InvalidPaymentMethodException("The payment intent already have an " +
                    "associated payment method.");
        }

        // entity manager persist changes without intervention
        paymentIntent.setPaymentMethod(paymentMethod);
        paymentIntent.transitionTo(PaymentIntentStatus.REQUIRES_CONFIRMATION);

        return paymentIntent;
    }

    @Transactional
    public PaymentIntent confirmPaymentIntent(UUID id) {
        PaymentIntent paymentIntent = findById(id);

        // verify if payment intent has an associated payment method
        if (paymentIntent.getPaymentMethod() == null) {
            throw new PaymentMethodRequiredException(id);
        }
        paymentIntent.transitionTo(PaymentIntentStatus.PROCESSING);

        // TODO: init processing payment action

        return paymentIntent;
    }

    private static PaymentIntent buildIntent(CreatePaymentIntentRequest request) {
        PaymentIntent paymentIntent = new PaymentIntent();
        paymentIntent.setAmount(request.amount());
        paymentIntent.setCurrency(request.currency());
        paymentIntent.setDescription(request.description());
        paymentIntent.setCustomer(Customer.builder().id(request.customerId()).build());
        paymentIntent.setMerchant(Merchant.builder().id(request.merchantId()).build());

        return paymentIntent;
    }
}
