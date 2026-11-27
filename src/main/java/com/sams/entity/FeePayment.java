package com.sams.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** One payment attempt. method is ONLINE (Razorpay) or QR (UPI QR, approved by faculty). status is PENDING, PAID or REJECTED. */
@Entity
@Getter @Setter @NoArgsConstructor
public class FeePayment {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long feeItemId;
    private String studentId;
    private long amount;
    @Column(name = "pay_method")
    private String method;
    @Column(name = "pay_status")
    private String status;
    private String razorpayOrderId;
    private String razorpayPaymentId;
    private String utr;
    private String decidedBy;
    private Instant createdAt = Instant.now();
    private Instant paidAt;
}
