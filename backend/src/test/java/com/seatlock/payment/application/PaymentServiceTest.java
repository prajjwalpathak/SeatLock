package com.seatlock.payment.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.seatlock.booking.application.BookingService;
import com.seatlock.booking.application.PayableBooking;
import com.seatlock.booking.domain.BookingStatus;
import com.seatlock.common.exception.ServiceUnavailableException;
import com.seatlock.payment.domain.Payment;
import com.seatlock.payment.domain.PaymentRepository;
import com.seatlock.payment.gateway.CreateOrderCommand;
import com.seatlock.payment.gateway.GatewayName;
import com.seatlock.payment.gateway.GatewayOrder;
import com.seatlock.payment.gateway.PaymentGateway;
import com.seatlock.payment.gateway.PaymentGatewayException;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

class PaymentServiceTest {

    static final UUID PUBLIC_ID = UUID.randomUUID();
    static final PayableBooking HELD = new PayableBooking(7L, PUBLIC_ID, BookingStatus.HELD, 120_000, "INR",
            Instant.parse("2026-12-01T10:08:00Z"));
    static final PayableBooking PENDING = new PayableBooking(7L, PUBLIC_ID, BookingStatus.PAYMENT_PENDING, 120_000,
            "INR", HELD.expiresAt());

    BookingService bookings = mock(BookingService.class);
    PaymentRepository payments = mock(PaymentRepository.class);
    PaymentGateway gateway = mock(PaymentGateway.class);
    TransactionTemplate tx = mock(TransactionTemplate.class);
    PaymentService service = new PaymentService(bookings, payments, gateway, tx);

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        when(tx.execute(any())).thenAnswer(inv -> ((TransactionCallback<Object>) inv.getArgument(0)).doInTransaction(null));
        when(bookings.checkPayable(1L, PUBLIC_ID.toString())).thenReturn(HELD);
        when(bookings.startPayment(1L, PUBLIC_ID.toString())).thenReturn(PENDING);
        when(payments.findFirstByBookingIdAndStatusIn(anyLong(), anyCollection())).thenReturn(Optional.empty());
        when(payments.findTopByBookingIdOrderByAttemptDesc(anyLong())).thenReturn(Optional.empty());
        when(payments.saveAndFlush(any(Payment.class))).thenAnswer(inv -> inv.getArgument(0));
        when(gateway.name()).thenReturn(GatewayName.MOCK);
    }

    @Test
    void createsTheOrderWithTheServerSideAmount() {
        when(gateway.createOrder(any())).thenReturn(order(120_000));

        PaymentService.OrderResult result = service.createOrder(1L, PUBLIC_ID.toString());

        assertThat(result.created()).isTrue();
        assertThat(result.order().orderId()).isEqualTo("order_mock_X");
        assertThat(result.order().amountPaise()).isEqualTo(120_000);
        assertThat(result.order().attempt()).isEqualTo(1);
        assertThat(result.order().payBy()).isEqualTo(HELD.expiresAt());
        assertThat(result.order().keyId()).isNull(); // gateway mock returns no checkout key
        ArgumentCaptor<CreateOrderCommand> cmd = ArgumentCaptor.forClass(CreateOrderCommand.class);
        verify(gateway).createOrder(cmd.capture());
        assertThat(cmd.getValue().amountPaise()).isEqualTo(120_000);
        assertThat(cmd.getValue().receipt()).isEqualTo(PUBLIC_ID.toString().replace("-", "") + "-1").hasSizeLessThanOrEqualTo(40);
        verify(bookings).startPayment(1L, PUBLIC_ID.toString());
    }

    @Test
    void anOpenOrderIsReturnedWithoutCallingTheGateway() {
        Payment open = new Payment(7L, 1, GatewayName.MOCK, "order_mock_OPEN", 120_000, "INR");
        when(payments.findFirstByBookingIdAndStatusIn(anyLong(), anyCollection())).thenReturn(Optional.of(open));

        PaymentService.OrderResult result = service.createOrder(1L, PUBLIC_ID.toString());

        assertThat(result.created()).isFalse();
        assertThat(result.order().orderId()).isEqualTo("order_mock_OPEN");
        verify(gateway, never()).createOrder(any());
        verifyNoInteractions(tx);
    }

    @Test
    void aGatewayFailureIs503AndStoresNothing() {
        when(gateway.createOrder(any())).thenThrow(new PaymentGatewayException("connect timed out"));

        assertThatThrownBy(() -> service.createOrder(1L, PUBLIC_ID.toString()))
                .isInstanceOf(ServiceUnavailableException.class)
                .satisfies(e -> assertThat(((ServiceUnavailableException) e).getCode()).isEqualTo("PAYMENTS_UNAVAILABLE"));
        verifyNoInteractions(tx);
        verify(payments, never()).saveAndFlush(any());
    }

    @Test
    void aGatewayAmountThatDiffersFromTheBookingIsRefused() {
        when(gateway.createOrder(any())).thenReturn(order(1));

        assertThatThrownBy(() -> service.createOrder(1L, PUBLIC_ID.toString()))
                .isInstanceOf(IllegalStateException.class);
        verify(payments, never()).saveAndFlush(any());
    }

    @Test
    void anAttemptAfterAFailureGetsTheNextNumber() {
        Payment failed = new Payment(7L, 2, GatewayName.MOCK, "order_mock_OLD", 120_000, "INR");
        failed.markFailed("declined", Instant.parse("2026-12-01T10:02:00Z"));
        when(payments.findTopByBookingIdOrderByAttemptDesc(7L)).thenReturn(Optional.of(failed));
        when(gateway.createOrder(any())).thenReturn(order(120_000));

        assertThat(service.createOrder(1L, PUBLIC_ID.toString()).order().attempt()).isEqualTo(3);
    }

    @Test
    void theCheckoutKeyIsReturnedForOrdersOfTheActiveGateway() {
        when(gateway.name()).thenReturn(GatewayName.RAZORPAY);
        when(gateway.checkoutKeyId()).thenReturn("rzp_test_abc");
        Payment open = new Payment(7L, 1, GatewayName.RAZORPAY, "order_Rz1", 120_000, "INR");
        Payment oldMock = new Payment(7L, 1, GatewayName.MOCK, "order_mock_1", 120_000, "INR");

        when(payments.findFirstByBookingIdAndStatusIn(anyLong(), anyCollection())).thenReturn(Optional.of(open));
        assertThat(service.createOrder(1L, PUBLIC_ID.toString()).order().keyId()).isEqualTo("rzp_test_abc");

        when(payments.findFirstByBookingIdAndStatusIn(anyLong(), anyCollection())).thenReturn(Optional.of(oldMock));
        assertThat(service.createOrder(1L, PUBLIC_ID.toString()).order().keyId()).isNull();
    }

    private static GatewayOrder order(long amount) {
        return new GatewayOrder(GatewayName.MOCK, "order_mock_X", amount, "INR", "r");
    }
}
