package com.payment.gateway.utility;

import com.payment.gateway.controller.DTO.CreatePaymentIntentRequest;

import java.util.Objects;

public class HashBuilder {

    private HashBuilder() {}

    public static String buildPaymentIntentHash(
            CreatePaymentIntentRequest request
    ) {
        String content = encodeField(request.amount().toPlainString())
                + encodeField(request.currency().name())
                + encodeField(request.merchantId().toString())
                + encodeField(request.customerId().toString())
                + encodeField(Objects.toString(request.description(), ""));

        return Sha256.hash(content);
    }

    private static String encodeField(String value) {
        return value.length() + ":" + value;
    }
}
