package com.payment.gateway.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.payment.gateway.config.JacksonConfig;
import com.payment.gateway.controller.DTO.CreatePaymentIntentRequest;
import com.payment.gateway.controller.DTO.AttachPaymentMethodRequest;
import com.payment.gateway.controller.advice.GlobalExceptionHandler;
import com.payment.gateway.entity.Customer;
import com.payment.gateway.entity.Merchant;
import com.payment.gateway.entity.PaymentIntent;
import com.payment.gateway.entity.enums.Currency;
import com.payment.gateway.entity.enums.PaymentIntentStatus;
import com.payment.gateway.exception.PaymentIntentNotFoundException;
import com.payment.gateway.exception.PaymentMethodRequiredException;
import com.payment.gateway.service.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = PaymentIntentController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import({JacksonConfig.class, GlobalExceptionHandler.class})
public class PaymentIntentControllerTest {

    @MockitoBean
    private PaymentIntentService paymentIntentService;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private MockMvc mockMvc;

    @Test
    void shouldCreatePaymentIntent() throws Exception {
        UUID id = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();
        UUID merchantId = UUID.randomUUID();
        Instant now = Instant.now();
        PaymentIntent paymentIntent = createIntent(id, now);

        CreatePaymentIntentRequest request = new CreatePaymentIntentRequest(
                paymentIntent.getAmount(), Currency.USD, "test intent", merchantId, customerId
        );

        // TODO: fix this method, add idempotency key
        when(paymentIntentService.create(request, ""))
                .thenReturn(paymentIntent);

        mockMvc.perform(post("/payment-intents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.amount").value(2.00))
                .andExpect(jsonPath("$.currency").value(Currency.USD.toString()))
                .andExpect(jsonPath("$.status").value(PaymentIntentStatus.REQUIRES_PAYMENT_METHOD.toString()))
                .andExpect(jsonPath("$.description").value("test intent"))
                .andExpect(jsonPath("$.createdAt").value(now.toString()));

        // TODO: fix this method, add idempotency key
        verify(paymentIntentService).create(any(CreatePaymentIntentRequest.class), "");
    }

    @Test
    void shouldGetPaymentIntent() throws Exception {
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        PaymentIntent intent = createIntent(id, now);

        when(paymentIntentService.findById(id)).thenReturn(intent);

        mockMvc.perform(get("/payment-intents/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.amount").value(2.00))
                .andExpect(jsonPath("$.currency").value("USD"))
                .andExpect(jsonPath("$.status").value("REQUIRES_PAYMENT_METHOD"));

        verify(paymentIntentService).findById(id);
    }

    @Test
    void shouldReturn404WhenPaymentIntentDoesNotExist() throws Exception {
        UUID id = UUID.randomUUID();
        when(paymentIntentService.findById(id)).thenThrow(new PaymentIntentNotFoundException(id));

        mockMvc.perform(get("/payment-intents/{id}", id))
                .andExpect(status().isNotFound());

        verify(paymentIntentService).findById(id);
    }

    @Test
    void shouldAttachPaymentMethod() throws Exception {
        UUID id = UUID.randomUUID();
        UUID paymentMethodId = UUID.randomUUID();
        PaymentIntent intent = createIntent(id, Instant.now());
        intent.transitionTo(PaymentIntentStatus.REQUIRES_CONFIRMATION);
        AttachPaymentMethodRequest request = new AttachPaymentMethodRequest(paymentMethodId);
        when(paymentIntentService.attachPaymentMethod(id, request)).thenReturn(intent);

        mockMvc.perform(post("/payment-intents/{id}/payment-method", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.status").value("REQUIRES_CONFIRMATION"));

        verify(paymentIntentService).attachPaymentMethod(eq(id), any(AttachPaymentMethodRequest.class));
    }

    @Test
    void shouldConfirmPaymentIntent() throws Exception {
        UUID id = UUID.randomUUID();
        PaymentIntent intent = createIntent(id, Instant.now());
        intent.transitionTo(PaymentIntentStatus.REQUIRES_CONFIRMATION);
        intent.transitionTo(PaymentIntentStatus.PROCESSING);
        when(paymentIntentService.confirmPaymentIntent(id)).thenReturn(intent);

        mockMvc.perform(post("/payment-intents/{id}/confirm", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.status").value("PROCESSING"));

        verify(paymentIntentService).confirmPaymentIntent(id);
    }

    @Test
    void shouldRejectInvalidCreatePaymentIntentRequest() throws Exception {
        CreatePaymentIntentRequest request = new CreatePaymentIntentRequest(
                BigDecimal.ZERO, null, "bad", null, null
        );

        mockMvc.perform(post("/payment-intents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());

        // TODO: fix this method, add valid idempotency key
        verify(paymentIntentService, never()).create(any(CreatePaymentIntentRequest.class), "");
    }

    @Test
    void shouldRejectAttachRequestWithoutPaymentMethodId() throws Exception {
        UUID id = UUID.randomUUID();

        mockMvc.perform(post("/payment-intents/{id}/payment-method", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());

        verify(paymentIntentService, never()).attachPaymentMethod(eq(id), any(AttachPaymentMethodRequest.class));
    }

    @Test
    void shouldReturnBadRequestWhenConfirmationRequiresPaymentMethod() throws Exception {
        UUID id = UUID.randomUUID();
        when(paymentIntentService.confirmPaymentIntent(id)).thenThrow(new PaymentMethodRequiredException(id));

        mockMvc.perform(post("/payment-intents/{id}/confirm", id))
                .andExpect(status().isBadRequest());

        verify(paymentIntentService).confirmPaymentIntent(id);
    }

    private static PaymentIntent createIntent(UUID id, Instant createdAt) {
        PaymentIntent intent = new PaymentIntent();
        intent.setId(id);
        intent.setAmount(new BigDecimal("2.00"));
        intent.setDescription("test intent");
        intent.setCurrency(Currency.USD);
        intent.setCreatedAt(createdAt);
        intent.setUpdatedAt(createdAt);
        intent.setCustomer(Customer.builder().id(UUID.randomUUID()).build());
        intent.setMerchant(Merchant.builder().id(UUID.randomUUID()).build());
        return intent;
    }
}
