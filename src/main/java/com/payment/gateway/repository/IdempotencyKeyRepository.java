package com.payment.gateway.repository;

import com.payment.gateway.entity.IdempotencyKey;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface IdempotencyKeyRepository extends JpaRepository<IdempotencyKey, UUID> {}
