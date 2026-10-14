package com.payment.gateway.service;

import com.payment.gateway.entity.IdempotencyKey;
import com.payment.gateway.repository.IdempotencyKeyRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class IdempotencyKeyService {

    private final IdempotencyKeyRepository repository;

    public IdempotencyKey findByMerchantIdAndIdempotencyKey(UUID merchantId, String key) {
        return repository.findByMerchantIdAndIdempotencyKey(merchantId, key);
    }

    public IdempotencyKey save(IdempotencyKey idempotencyKey) {
        return repository.save(idempotencyKey);
    }
}
