package com.payment.gateway.service;

import com.payment.gateway.controller.DTO.AttachPaymentMethodRequest;
import com.payment.gateway.controller.DTO.CreatePaymentIntentRequest;
import com.payment.gateway.entity.Customer;
import com.payment.gateway.entity.PaymentIntent;
import com.payment.gateway.entity.PaymentMethod;
import com.payment.gateway.entity.enums.Currency;
import com.payment.gateway.entity.enums.MerchantStatus;
import com.payment.gateway.entity.enums.PaymentIntentStatus;
import com.payment.gateway.exception.CustomerNotFoundException;
import com.payment.gateway.exception.InvalidPaymentMethodException;
import com.payment.gateway.exception.MerchantNotFoundException;
import com.payment.gateway.exception.PaymentMethodRequiredException;
import com.payment.gateway.repository.CustomerRepository;
import com.payment.gateway.repository.MerchantRepository;
import com.payment.gateway.repository.PaymentIntentRepository;
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
    private PaymentMethodService paymentMethodService;
    @Mock
    private CustomerRepository customerRepository;
    @Mock
    private MerchantRepository merchantRepository;

    @InjectMocks
    private PaymentIntentService service;

    @Test
    void createSavesIntentWhenCustomerAndActiveMerchantExist() {
        UUID customerId = UUID.randomUUID();
        UUID merchantId = UUID.randomUUID();
        CreatePaymentIntentRequest request = new CreatePaymentIntentRequest(
                new BigDecimal("12.50"), Currency.USD, "order 123", merchantId, customerId);
        when(customerRepository.existsByIdAndDeletedFalse(customerId)).thenReturn(true);
        when(merchantRepository.existsByIdAndStatus(merchantId, MerchantStatus.ACTIVE)).thenReturn(true);
        when(paymentIntentRepository.save(any(PaymentIntent.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        // TODO: fix this method
        PaymentIntent result = service.create(request, "");

        ArgumentCaptor<PaymentIntent> captor = ArgumentCaptor.forClass(PaymentIntent.class);
        verify(paymentIntentRepository).save(captor.capture());
        PaymentIntent saved = captor.getValue();
        assertSame(saved, result);
        assertEquals(new BigDecimal("12.50"), saved.getAmount());
        assertEquals(Currency.USD, saved.getCurrency());
        assertEquals("order 123", saved.getDescription());
        assertEquals(customerId, saved.getCustomer().getId());
        assertEquals(merchantId, saved.getMerchant().getId());
        assertEquals(PaymentIntentStatus.REQUIRES_PAYMENT_METHOD, saved.getStatus());
    }

    @Test
    void createFailsWhenCustomerDoesNotExist() {
        UUID customerId = UUID.randomUUID();
        CreatePaymentIntentRequest request = createRequest(UUID.randomUUID(), customerId);
        when(customerRepository.existsByIdAndDeletedFalse(customerId)).thenReturn(false);

        // TODO: fix this method
        assertThrows(CustomerNotFoundException.class, () -> service.create(request, ""));

        verifyNoInteractions(merchantRepository, paymentIntentRepository);
    }

    @Test
    void createFailsWhenMerchantIsNotActive() {
        UUID customerId = UUID.randomUUID();
        UUID merchantId = UUID.randomUUID();
        CreatePaymentIntentRequest request = createRequest(merchantId, customerId);
        when(customerRepository.existsByIdAndDeletedFalse(customerId)).thenReturn(true);
        when(merchantRepository.existsByIdAndStatus(merchantId, MerchantStatus.ACTIVE)).thenReturn(false);

        // TODO: fix this method
        assertThrows(MerchantNotFoundException.class, () -> service.create(request, ""));

        verify(paymentIntentRepository, never()).save(any());
    }

    @Test
    void attachPaymentMethodAssociatesMethodAndMovesIntentToRequiresConfirmation() {
        UUID intentId = UUID.randomUUID();
        UUID paymentMethodId = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();
        PaymentIntent intent = intent(intentId, customerId);
        PaymentMethod method = paymentMethod(paymentMethodId, customerId);
        AttachPaymentMethodRequest request = new AttachPaymentMethodRequest(paymentMethodId);
        when(paymentMethodService.findById(paymentMethodId)).thenReturn(method);
        when(paymentIntentRepository.findById(intentId)).thenReturn(Optional.of(intent));

        PaymentIntent result = service.attachPaymentMethod(intentId, request);

        assertSame(intent, result);
        assertSame(method, result.getPaymentMethod());
        assertEquals(PaymentIntentStatus.REQUIRES_CONFIRMATION, result.getStatus());
        verify(paymentMethodService).findById(paymentMethodId);
        verify(paymentIntentRepository).findById(intentId);
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
    void confirmMovesIntentWithPaymentMethodToProcessing() {
        UUID intentId = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();
        PaymentIntent intent = intent(intentId, customerId);
        intent.transitionTo(PaymentIntentStatus.REQUIRES_CONFIRMATION);
        intent.setPaymentMethod(paymentMethod(UUID.randomUUID(), customerId));
        when(paymentIntentRepository.findById(intentId)).thenReturn(Optional.of(intent));

        PaymentIntent result = service.confirmPaymentIntent(intentId);

        assertSame(intent, result);
        assertEquals(PaymentIntentStatus.PROCESSING, result.getStatus());
        verify(paymentIntentRepository).findById(intentId);
    }

    @Test
    void confirmFailsWhenIntentHasNoPaymentMethod() {
        UUID intentId = UUID.randomUUID();
        PaymentIntent intent = intent(intentId, UUID.randomUUID());
        intent.transitionTo(PaymentIntentStatus.REQUIRES_CONFIRMATION);
        when(paymentIntentRepository.findById(intentId)).thenReturn(Optional.of(intent));

        assertThrows(PaymentMethodRequiredException.class,
                () -> service.confirmPaymentIntent(intentId));

        assertEquals(PaymentIntentStatus.REQUIRES_CONFIRMATION, intent.getStatus());
    }

    private static CreatePaymentIntentRequest createRequest(UUID merchantId, UUID customerId) {
        return new CreatePaymentIntentRequest(BigDecimal.ONE, Currency.USD, "test", merchantId, customerId);
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
