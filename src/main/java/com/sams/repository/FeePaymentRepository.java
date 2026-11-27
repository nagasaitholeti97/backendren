package com.sams.repository;

import com.sams.entity.FeePayment;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FeePaymentRepository extends JpaRepository<FeePayment, Long> {
    List<FeePayment> findByStudentIdOrderByIdDesc(String studentId);

    List<FeePayment> findByFeeItemId(Long feeItemId);

    List<FeePayment> findByStatusOrderByIdAsc(String status);

    Optional<FeePayment> findByRazorpayOrderIdAndStudentId(String orderId, String studentId);

    boolean existsByUtrIgnoreCase(String utr);
}
