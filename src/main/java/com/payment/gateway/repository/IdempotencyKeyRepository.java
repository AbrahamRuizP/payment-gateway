package com.payment.gateway.repository;

import com.payment.gateway.entity.IdempotencyKey;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface IdempotencyKeyRepository extends JpaRepository<IdempotencyKey, UUID> {
    IdempotencyKey findByMerchantIdAndIdempotencyKey(UUID merchantId, String idempotencyKey);
}
