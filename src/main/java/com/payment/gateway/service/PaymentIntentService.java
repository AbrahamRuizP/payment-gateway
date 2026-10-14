package com.payment.gateway.service;

import com.payment.gateway.controller.DTO.AttachPaymentMethodRequest;
import com.payment.gateway.controller.DTO.CreatePaymentIntentRequest;
import com.payment.gateway.entity.*;
import com.payment.gateway.entity.enums.KeyStatus;
import com.payment.gateway.entity.enums.MerchantStatus;
import com.payment.gateway.entity.enums.PaymentIntentStatus;
import com.payment.gateway.exception.*;
import com.payment.gateway.repository.CustomerRepository;
import com.payment.gateway.repository.MerchantRepository;
import com.payment.gateway.repository.PaymentIntentRepository;
import com.payment.gateway.utility.HashBuilder;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class PaymentIntentService {

    private final PaymentIntentRepository paymentIntentRepository;
    private final IdempotencyKeyService idempotencyKeyService;
    private final PaymentMethodService paymentMethodService;
    private final CustomerRepository customerRepository;
    private final MerchantRepository merchantRepository;

    public PaymentIntent findById(UUID id) {
        return paymentIntentRepository.findById(id)
                .orElseThrow(() -> new PaymentIntentNotFoundException(id));
    }

    @Transactional
    public PaymentIntent create(
            CreatePaymentIntentRequest request,
            String idempotencyKey
    ) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("Invalid idempotency key");
        }

        IdempotencyKey idempotencyKeyFound
                = idempotencyKeyService.findByMerchantIdAndIdempotencyKey(request.merchantId(), idempotencyKey);

        if (idempotencyKeyFound != null) {
            String currHash = HashBuilder.buildPaymentIntentHash(request);
            String prevHash = idempotencyKeyFound.getRequestHash();
            KeyStatus status = idempotencyKeyFound.getStatus();

            if (!currHash.equals(prevHash)) {
                throw new IdempotencyKeyReuseWithDifferentRequestException(
                        "The idempotency key was already used with a different request."
                );
            }

            if (status.equals(KeyStatus.COMPLETED)) {
                PaymentIntent existingIntent = idempotencyKeyFound.getPaymentIntent();

                if (existingIntent == null) {
                    throw new IllegalStateException(
                            "Completed idempotency key has no associated payment intent."
                    );
                }

                return existingIntent;
            }

            if (idempotencyKeyFound.getStatus() == KeyStatus.PROCESSING) {
                throw new IdempotencyOperationInProgressException(
                        "An operation with this idempotency key is still in progress."
                );
            }

            throw new IllegalStateException(
                    "Unsupported idempotency key status: " + idempotencyKeyFound.getStatus()
            );
        }

        if (!customerRepository.existsByIdAndDeletedFalse(request.customerId())) {
            throw new CustomerNotFoundException(request.customerId());

        } else if (!merchantRepository.existsByIdAndStatus(request.merchantId(), MerchantStatus.ACTIVE)) {
            throw new MerchantNotFoundException(request.merchantId());
        }

        // saving payment intent
        PaymentIntent intentBuilt = buildIntent(request);
        PaymentIntent intentSaved = paymentIntentRepository.save(intentBuilt);

        // creating idempotency key
        IdempotencyKey idempotencyKeyBuilt
                = buildIdempotencyKey(intentSaved, request, idempotencyKey);
        idempotencyKeyService.save(idempotencyKeyBuilt);

        return intentSaved;
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
        if (paymentIntent.getPaymentMethod() != null) {
            throw new InvalidPaymentMethodException("The payment intent already have " +
                    "associated the current payment method.");
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
        } else if (paymentIntent.getStatus() == PaymentIntentStatus.PROCESSING) {
            return paymentIntent;
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

    private static IdempotencyKey buildIdempotencyKey(
            PaymentIntent paymentIntent,
            CreatePaymentIntentRequest request,
            String key
    ) {
        IdempotencyKey idempotencyKey = new IdempotencyKey();
        idempotencyKey.setMerchant(Merchant.builder().id(request.merchantId()).build());
        idempotencyKey.setRequestHash(HashBuilder.buildPaymentIntentHash(request));
        idempotencyKey.setStatus(KeyStatus.COMPLETED);
        idempotencyKey.setPaymentIntent(paymentIntent);
        idempotencyKey.setIdempotencyKey(key);

        return idempotencyKey;
    }
}
