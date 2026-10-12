
ALTER TABLE payment_intent
ADD IF NOT EXISTS payment_method_id UUID;

ALTER TABLE payment_intent
ADD CONSTRAINT fk_payment_intent_payment_method
FOREIGN KEY (payment_method_id)
REFERENCES payment_method (id);