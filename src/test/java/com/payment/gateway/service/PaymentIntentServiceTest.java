package com.payment.gateway.service;

import com.payment.gateway.controller.DTO.AttachPaymentMethodRequest;
import com.payment.gateway.controller.DTO.CreatePaymentIntentRequest;
import com.payment.gateway.entity.Customer;
import com.payment.gateway.entity.IdempotencyKey;
import com.payment.gateway.entity.PaymentIntent;
import com.payment.gateway.entity.PaymentMethod;
import com.payment.gateway.entity.enums.Currency;
import com.payment.gateway.entity.enums.KeyStatus;
import com.payment.gateway.entity.enums.MerchantStatus;
import com.payment.gateway.entity.enums.PaymentIntentStatus;
import com.payment.gateway.exception.CustomerNotFoundException;
import com.payment.gateway.exception.IdempotencyKeyReuseWithDifferentRequestException;
import com.payment.gateway.exception.IdempotencyOperationInProgressException;
import com.payment.gateway.exception.InvalidPaymentMethodException;
import com.payment.gateway.exception.MerchantNotFoundException;
import com.payment.gateway.exception.PaymentMethodRequiredException;
import com.payment.gateway.repository.CustomerRepository;
import com.payment.gateway.repository.MerchantRepository;
import com.payment.gateway.repository.PaymentIntentRepository;
import com.payment.gateway.utility.HashBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PaymentIntentServiceTest {

    @Mock
    private PaymentIntentRepository paymentIntentRepository;
    @Mock
    private IdempotencyKeyService idempotencyKeyService;
    @Mock
    private PaymentMethodService paymentMethodService;
    @Mock
    private CustomerRepository customerRepository;
    @Mock
    private MerchantRepository merchantRepository;

    @InjectMocks
    private PaymentIntentService service;

    @Test
    void createSavesIntentAndCompletedIdempotencyKeyForNewRequest() {
        UUID customerId = UUID.randomUUID();
        UUID merchantId = UUID.randomUUID();
        String key = "request-123";
        CreatePaymentIntentRequest request = createRequest(merchantId, customerId);
        when(idempotencyKeyService.findByMerchantIdAndIdempotencyKey(merchantId, key)).thenReturn(null);
        when(customerRepository.existsByIdAndDeletedFalse(customerId)).thenReturn(true);
        when(merchantRepository.existsByIdAndStatus(merchantId, MerchantStatus.ACTIVE)).thenReturn(true);
        when(paymentIntentRepository.save(any(PaymentIntent.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(idempotencyKeyService.save(any(IdempotencyKey.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        PaymentIntent result = service.create(request, key);

        ArgumentCaptor<PaymentIntent> intentCaptor = ArgumentCaptor.forClass(PaymentIntent.class);
        verify(paymentIntentRepository).save(intentCaptor.capture());
        PaymentIntent savedIntent = intentCaptor.getValue();
        assertSame(savedIntent, result);
        assertEquals(new BigDecimal("12.50"), savedIntent.getAmount());
        assertEquals(Currency.USD, savedIntent.getCurrency());
        assertEquals("order 123", savedIntent.getDescription());
        assertEquals(customerId, savedIntent.getCustomer().getId());
        assertEquals(merchantId, savedIntent.getMerchant().getId());
        assertEquals(PaymentIntentStatus.REQUIRES_PAYMENT_METHOD, savedIntent.getStatus());

        ArgumentCaptor<IdempotencyKey> keyCaptor = ArgumentCaptor.forClass(IdempotencyKey.class);
        verify(idempotencyKeyService).save(keyCaptor.capture());
        IdempotencyKey savedKey = keyCaptor.getValue();
        assertEquals(key, savedKey.getIdempotencyKey());
        assertEquals(HashBuilder.buildPaymentIntentHash(request), savedKey.getRequestHash());
        assertEquals(KeyStatus.COMPLETED, savedKey.getStatus());
        assertSame(savedIntent, savedKey.getPaymentIntent());
        assertEquals(merchantId, savedKey.getMerchant().getId());
        assertEquals(customerId, savedKey.getCustomer().getId());
    }

    @Test
    void createRejectsMissingOrBlankIdempotencyKeyBeforeCallingDependencies() {
        CreatePaymentIntentRequest request = createRequest(UUID.randomUUID(), UUID.randomUUID());

        assertAll(
                () -> assertThrows(IllegalArgumentException.class, () -> service.create(request, null)),
                () -> assertThrows(IllegalArgumentException.class, () -> service.create(request, " "))
        );

        verifyNoInteractions(idempotencyKeyService, customerRepository, merchantRepository, paymentIntentRepository);
    }

    @Test
    void createReturnsExistingIntentWhenKeyAndRequestMatch() {
        UUID merchantId = UUID.randomUUID();
        CreatePaymentIntentRequest request = createRequest(merchantId, UUID.randomUUID());
        PaymentIntent existingIntent = intent(UUID.randomUUID(), request.customerId());
        IdempotencyKey existingKey = idempotencyKey(request, "request-123", KeyStatus.COMPLETED, existingIntent);
        when(idempotencyKeyService.findByMerchantIdAndIdempotencyKey(merchantId, "request-123"))
                .thenReturn(existingKey);

        PaymentIntent result = service.create(request, "request-123");

        assertSame(existingIntent, result);
        verifyNoInteractions(customerRepository, merchantRepository, paymentIntentRepository);
        verify(idempotencyKeyService, never()).save(any());
    }

    @Test
    void createRejectsReusingKeyWithDifferentRequest() {
        UUID merchantId = UUID.randomUUID();
        CreatePaymentIntentRequest request = createRequest(merchantId, UUID.randomUUID());
        IdempotencyKey existingKey = idempotencyKey(request, "request-123", KeyStatus.COMPLETED,
                intent(UUID.randomUUID(), request.customerId()));
        existingKey.setRequestHash("different-hash");
        when(idempotencyKeyService.findByMerchantIdAndIdempotencyKey(merchantId, "request-123"))
                .thenReturn(existingKey);

        assertThrows(IdempotencyKeyReuseWithDifferentRequestException.class,
                () -> service.create(request, "request-123"));

        verifyNoInteractions(customerRepository, merchantRepository, paymentIntentRepository);
        verify(idempotencyKeyService, never()).save(any());
    }

    @Test
    void createFailsWhenCompletedKeyHasNoAssociatedIntent() {
        UUID merchantId = UUID.randomUUID();
        CreatePaymentIntentRequest request = createRequest(merchantId, UUID.randomUUID());
        IdempotencyKey existingKey = idempotencyKey(request, "request-123", KeyStatus.COMPLETED, null);
        when(idempotencyKeyService.findByMerchantIdAndIdempotencyKey(merchantId, "request-123"))
                .thenReturn(existingKey);

        assertThrows(IllegalStateException.class, () -> service.create(request, "request-123"));

        verifyNoInteractions(customerRepository, merchantRepository, paymentIntentRepository);
        verify(idempotencyKeyService, never()).save(any());
    }

    @Test
    void createRejectsMatchingKeyWhileOriginalOperationIsProcessing() {
        UUID merchantId = UUID.randomUUID();
        CreatePaymentIntentRequest request = createRequest(merchantId, UUID.randomUUID());
        IdempotencyKey existingKey = idempotencyKey(request, "request-123", KeyStatus.PROCESSING, null);
        when(idempotencyKeyService.findByMerchantIdAndIdempotencyKey(merchantId, "request-123"))
                .thenReturn(existingKey);

        assertThrows(IdempotencyOperationInProgressException.class,
                () -> service.create(request, "request-123"));

        verifyNoInteractions(customerRepository, merchantRepository, paymentIntentRepository);
        verify(idempotencyKeyService, never()).save(any());
    }

    @Test
    void createFailsWhenCustomerDoesNotExist() {
        UUID customerId = UUID.randomUUID();
        UUID merchantId = UUID.randomUUID();
        CreatePaymentIntentRequest request = createRequest(merchantId, customerId);
        when(idempotencyKeyService.findByMerchantIdAndIdempotencyKey(merchantId, "request-123")).thenReturn(null);
        when(customerRepository.existsByIdAndDeletedFalse(customerId)).thenReturn(false);

        assertThrows(CustomerNotFoundException.class, () -> service.create(request, "request-123"));

        verifyNoInteractions(merchantRepository, paymentIntentRepository);
        verify(idempotencyKeyService, never()).save(any());
    }

    @Test
    void createFailsWhenMerchantIsNotActive() {
        UUID customerId = UUID.randomUUID();
        UUID merchantId = UUID.randomUUID();
        CreatePaymentIntentRequest request = createRequest(merchantId, customerId);
        when(idempotencyKeyService.findByMerchantIdAndIdempotencyKey(merchantId, "request-123")).thenReturn(null);
        when(customerRepository.existsByIdAndDeletedFalse(customerId)).thenReturn(true);
        when(merchantRepository.existsByIdAndStatus(merchantId, MerchantStatus.ACTIVE)).thenReturn(false);

        assertThrows(MerchantNotFoundException.class, () -> service.create(request, "request-123"));

        verify(paymentIntentRepository, never()).save(any());
        verify(idempotencyKeyService, never()).save(any());
    }

    @Test
    void attachPaymentMethodAssociatesMethodAndMovesIntentToRequiresConfirmation() {
        UUID intentId = UUID.randomUUID();
        UUID paymentMethodId = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();
        PaymentIntent intent = intent(intentId, customerId);
        PaymentMethod method = paymentMethod(paymentMethodId, customerId);
        when(paymentMethodService.findById(paymentMethodId)).thenReturn(method);
        when(paymentIntentRepository.findById(intentId)).thenReturn(Optional.of(intent));

        PaymentIntent result = service.attachPaymentMethod(intentId, new AttachPaymentMethodRequest(paymentMethodId));

        assertSame(intent, result);
        assertSame(method, result.getPaymentMethod());
        assertEquals(PaymentIntentStatus.REQUIRES_CONFIRMATION, result.getStatus());
    }

    @Test
    void attachPaymentMethodFailsWhenCustomersDoNotMatch() {
        UUID intentId = UUID.randomUUID();
        UUID paymentMethodId = UUID.randomUUID();
        PaymentIntent intent = intent(intentId, UUID.randomUUID());
        PaymentMethod method = paymentMethod(paymentMethodId, UUID.randomUUID());
        when(paymentMethodService.findById(paymentMethodId)).thenReturn(method);
        when(paymentIntentRepository.findById(intentId)).thenReturn(Optional.of(intent));

        assertThrows(InvalidPaymentMethodException.class,
                () -> service.attachPaymentMethod(intentId, new AttachPaymentMethodRequest(paymentMethodId)));

        assertNull(intent.getPaymentMethod());
        assertEquals(PaymentIntentStatus.REQUIRES_PAYMENT_METHOD, intent.getStatus());
    }

    @Test
    void attachPaymentMethodFailsWhenIntentAlreadyHasMethod() {
        UUID intentId = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();
        UUID paymentMethodId = UUID.randomUUID();
        PaymentIntent intent = intent(intentId, customerId);
        intent.setPaymentMethod(paymentMethod(UUID.randomUUID(), customerId));
        PaymentMethod method = paymentMethod(paymentMethodId, customerId);
        when(paymentMethodService.findById(paymentMethodId)).thenReturn(method);
        when(paymentIntentRepository.findById(intentId)).thenReturn(Optional.of(intent));

        assertThrows(InvalidPaymentMethodException.class,
                () -> service.attachPaymentMethod(intentId, new AttachPaymentMethodRequest(paymentMethodId)));

        assertNotSame(method, intent.getPaymentMethod());
        assertEquals(PaymentIntentStatus.REQUIRES_PAYMENT_METHOD, intent.getStatus());
    }

    @Test
    void confirmMovesIntentWithPaymentMethodToProcessing() {
        UUID intentId = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();
        PaymentIntent intent = intent(intentId, customerId);
        intent.setPaymentMethod(paymentMethod(UUID.randomUUID(), customerId));
        intent.transitionTo(PaymentIntentStatus.REQUIRES_CONFIRMATION);
        when(paymentIntentRepository.findById(intentId)).thenReturn(Optional.of(intent));

        PaymentIntent result = service.confirmPaymentIntent(intentId);

        assertSame(intent, result);
        assertEquals(PaymentIntentStatus.PROCESSING, result.getStatus());
    }

    @Test
    void confirmFailsWhenIntentHasNoPaymentMethod() {
        UUID intentId = UUID.randomUUID();
        PaymentIntent intent = intent(intentId, UUID.randomUUID());
        when(paymentIntentRepository.findById(intentId)).thenReturn(Optional.of(intent));

        assertThrows(PaymentMethodRequiredException.class, () -> service.confirmPaymentIntent(intentId));

        assertEquals(PaymentIntentStatus.REQUIRES_PAYMENT_METHOD, intent.getStatus());
    }

    @Test
    void confirmIsIdempotentWhenIntentIsAlreadyProcessing() {
        UUID intentId = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();
        PaymentIntent intent = intent(intentId, customerId);
        intent.setPaymentMethod(paymentMethod(UUID.randomUUID(), customerId));
        intent.transitionTo(PaymentIntentStatus.REQUIRES_CONFIRMATION);
        intent.transitionTo(PaymentIntentStatus.PROCESSING);
        when(paymentIntentRepository.findById(intentId)).thenReturn(Optional.of(intent));

        assertSame(intent, service.confirmPaymentIntent(intentId));
        assertEquals(PaymentIntentStatus.PROCESSING, intent.getStatus());
    }

    private static CreatePaymentIntentRequest createRequest(UUID merchantId, UUID customerId) {
        return new CreatePaymentIntentRequest(new BigDecimal("12.50"), Currency.USD,
                "order 123", merchantId, customerId);
    }

    private static IdempotencyKey idempotencyKey(
            CreatePaymentIntentRequest request,
            String key,
            KeyStatus status,
            PaymentIntent intent
    ) {
        IdempotencyKey idempotencyKey = new IdempotencyKey();
        idempotencyKey.setIdempotencyKey(key);
        idempotencyKey.setRequestHash(HashBuilder.buildPaymentIntentHash(request));
        idempotencyKey.setStatus(status);
        idempotencyKey.setPaymentIntent(intent);
        return idempotencyKey;
    }

    private static PaymentIntent intent(UUID id, UUID customerId) {
        PaymentIntent intent = new PaymentIntent();
        intent.setId(id);
        intent.setCustomer(Customer.builder().id(customerId).build());
        return intent;
    }

    private static PaymentMethod paymentMethod(UUID id, UUID customerId) {
        return PaymentMethod.builder()
                .id(id)
                .customer(Customer.builder().id(customerId).build())
                .build();
    }
}
