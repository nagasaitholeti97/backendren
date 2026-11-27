package com.sams.controller;

import com.sams.service.FeeService;
import com.sams.service.FeeService.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.security.Principal;
import java.time.LocalDate;
import java.util.List;
import org.springframework.web.bind.annotation.*;

/** Student: see fees and pay. Faculty: create fees and approve QR payments. Roles are enforced by the /api/student and /api/faculty rules. */
@RestController
public class FeeController {

    public record Verify(@NotBlank String orderId, @NotBlank String paymentId, @NotBlank String signature) {}

    public record QrSubmit(@NotBlank String utr) {}

    public record NewFee(@NotBlank String title, @NotNull Long amount, @NotNull LocalDate dueDate, String sectionId) {}

    private final FeeService fees;

    public FeeController(FeeService fees) {
        this.fees = fees;
    }

    // ---- student ----
    @GetMapping("/api/student/fees")
    public FeeConfig myFees(Principal me) {
        return fees.forStudent(me.getName());
    }

    @PostMapping("/api/student/fees/{id}/order")
    public OrderOut order(Principal me, @PathVariable Long id) {
        return fees.createOrder(me.getName(), id);
    }

    @PostMapping("/api/student/fees/{id}/verify")
    public Msg verify(Principal me, @PathVariable Long id, @Valid @RequestBody Verify b) {
        return fees.verify(me.getName(), id, b.orderId(), b.paymentId(), b.signature());
    }

    @PostMapping("/api/student/fees/{id}/qr")
    public Msg qr(Principal me, @PathVariable Long id, @Valid @RequestBody QrSubmit b) {
        return fees.submitQr(me.getName(), id, b.utr());
    }

    // ---- faculty ----
    @GetMapping("/api/faculty/fees")
    public List<FacultyFeeRow> list() {
        return fees.forFaculty();
    }

    @PostMapping("/api/faculty/fees")
    public FacultyFeeRow create(Principal me, @Valid @RequestBody NewFee b) {
        return fees.create(me.getName(), b.title(), b.amount(), b.dueDate(), b.sectionId());
    }

    @GetMapping("/api/faculty/fees/pending")
    public List<PendingRow> pending() {
        return fees.pending();
    }

    @PostMapping("/api/faculty/fees/payments/{paymentId}/approve")
    public Msg approve(Principal me, @PathVariable Long paymentId) {
        return fees.decide(me.getName(), paymentId, true);
    }

    @PostMapping("/api/faculty/fees/payments/{paymentId}/reject")
    public Msg reject(Principal me, @PathVariable Long paymentId) {
        return fees.decide(me.getName(), paymentId, false);
    }
}
