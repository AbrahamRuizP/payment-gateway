CREATE TABLE idempotency_key (

    id UUID PRIMARY KEY,
    idempotency_key VARCHAR(100) NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    response_status INTEGER,
    status VARCHAR(20) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,

    payment_intent_id UUID,
    merchant_id UUID NOT NULL,
    customer_id UUID,

    CONSTRAINT fk_idempotency_key_merchant
        FOREIGN KEY (merchant_id)
            REFERENCES merchant (id),

    CONSTRAINT fk_idempotency_key_customer
        FOREIGN KEY (customer_id)
            REFERENCES customer (id),

    CONSTRAINT fk_idempotency_key_payment_intent
        FOREIGN KEY (payment_intent_id)
            REFERENCES payment_intent (id),

    CONSTRAINT uq_idempotency_key_merchant_key
        UNIQUE (merchant_id, idempotency_key)
);

