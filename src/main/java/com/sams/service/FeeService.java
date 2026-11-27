package com.sams.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sams.entity.FeeItem;
import com.sams.entity.FeePayment;
import com.sams.entity.Student;
import com.sams.exception.ApiException;
import com.sams.repository.FeeItemRepository;
import com.sams.repository.FeePaymentRepository;
import com.sams.repository.StudentRepository;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Fees: items set by faculty, online payment through Razorpay, and UPI QR payments that faculty approve. */
@Service
public class FeeService {

    private static final Logger log = LoggerFactory.getLogger(FeeService.class);

    // ---- what the frontend receives (public so controllers can use them) ----
    public record FeeRow(Long id, String title, long amount, String dueDate, String status, String method, String paidAt) {}

    public record FeeConfig(String upiId, String payeeName, boolean onlineEnabled, String razorpayKeyId, List<FeeRow> items) {}

    public record OrderOut(String orderId, long amountPaise, String currency, String keyId) {}

    public record FacultyFeeRow(Long id, String title, long amount, String dueDate, String sectionId, int students, int paid, int pending) {}

    public record PendingRow(Long paymentId, String studentId, String studentName, String feeTitle, long amount, String utr, String submittedAt) {}

    public record Msg(String message) {}

    private final FeeItemRepository items;
    private final FeePaymentRepository payments;
    private final StudentRepository students;
    private final MailService mail;
    private final String upiId;
    private final String payeeName;
    private final String keyId;
    private final String keySecret;
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();

    public FeeService(FeeItemRepository items, FeePaymentRepository payments, StudentRepository students, MailService mail,
                      @Value("${SAMS_UPI_ID:}") String upiId,
                      @Value("${SAMS_PAYEE_NAME:SAMS College}") String payeeName,
                      @Value("${RAZORPAY_KEY_ID:}") String keyId,
                      @Value("${RAZORPAY_KEY_SECRET:}") String keySecret) {
        this.items = items;
        this.payments = payments;
        this.students = students;
        this.mail = mail;
        this.upiId = upiId.trim();
        this.payeeName = payeeName.trim();
        this.keyId = keyId.trim();
        this.keySecret = keySecret.trim();
    }

    private boolean onlineEnabled() {
        return !keyId.isBlank() && !keySecret.isBlank();
    }

    // =====================  STUDENT  =====================

    @Transactional(readOnly = true)
    public FeeConfig forStudent(String studentId) {
        Student s = students.findById(studentId).orElseThrow(() -> ApiException.notFound("Student"));
        List<FeePayment> mine = payments.findByStudentIdOrderByIdDesc(studentId);
        List<FeeRow> rows = new ArrayList<>();
        for (FeeItem f : items.findBySectionIdIsNullOrSectionIdOrderByDueDateAscIdAsc(s.getSection().getId())) {
            rows.add(row(f, mine));
        }
        return new FeeConfig(upiId, payeeName, onlineEnabled(), keyId, rows);
    }

    private FeeRow row(FeeItem f, List<FeePayment> mine) {
        FeePayment paid = mine.stream().filter(p -> p.getFeeItemId().equals(f.getId()) && "PAID".equals(p.getStatus())).findFirst().orElse(null);
        if (paid != null) {
            return new FeeRow(f.getId(), f.getTitle(), f.getAmount(), String.valueOf(f.getDueDate()), "PAID", paid.getMethod(),
                paid.getPaidAt() == null ? null : paid.getPaidAt().toString());
        }
        boolean pending = mine.stream().anyMatch(p -> p.getFeeItemId().equals(f.getId()) && "PENDING".equals(p.getStatus()) && "QR".equals(p.getMethod()));
        String status = pending ? "PENDING"
            : f.getDueDate() != null && f.getDueDate().isBefore(LocalDate.now()) ? "OVERDUE" : "DUE";
        return new FeeRow(f.getId(), f.getTitle(), f.getAmount(), String.valueOf(f.getDueDate()), status, null, null);
    }

    private FeeItem feeFor(String studentId, Long feeId) {
        Student s = students.findById(studentId).orElseThrow(() -> ApiException.notFound("Student"));
        FeeItem f = items.findById(feeId).orElseThrow(() -> ApiException.notFound("Fee"));
        if (f.getSectionId() != null && !f.getSectionId().equals(s.getSection().getId())) throw ApiException.notFound("Fee");
        return f;
    }

    private void ensureNotPaid(String studentId, Long feeId) {
        boolean paid = payments.findByStudentIdOrderByIdDesc(studentId).stream()
            .anyMatch(p -> p.getFeeItemId().equals(feeId) && "PAID".equals(p.getStatus()));
        if (paid) throw ApiException.badRequest("This fee is already paid.");
    }

    /** Creates a Razorpay order for the fee. The browser then opens Razorpay Checkout with the returned orderId. */
    @Transactional
    public OrderOut createOrder(String studentId, Long feeId) {
        if (!onlineEnabled()) throw ApiException.badRequest("Online payment is not set up yet. Use the QR option.");
        FeeItem f = feeFor(studentId, feeId);
        ensureNotPaid(studentId, feeId);
        long paise = f.getAmount() * 100;
        try {
            String payload = json.writeValueAsString(Map.of("amount", paise, "currency", "INR", "receipt", "fee" + feeId + "-" + System.currentTimeMillis() % 1_000_000_000L));
            String basic = Base64.getEncoder().encodeToString((keyId + ":" + keySecret).getBytes(StandardCharsets.UTF_8));
            HttpRequest req = HttpRequest.newBuilder(URI.create("https://api.razorpay.com/v1/orders"))
                .timeout(Duration.ofSeconds(12))
                .header("Authorization", "Basic " + basic)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() / 100 != 2) {
                log.error("Razorpay order failed ({}): {}", res.statusCode(), res.body());
                throw ApiException.badRequest("Could not start the payment. Please try again.");
            }
            JsonNode n = json.readTree(res.body());
            String orderId = n.path("id").asText();
            FeePayment p = new FeePayment();
            p.setFeeItemId(feeId);
            p.setStudentId(studentId);
            p.setAmount(f.getAmount());
            p.setMethod("ONLINE");
            p.setStatus("PENDING");
            p.setRazorpayOrderId(orderId);
            payments.save(p);
            return new OrderOut(orderId, paise, "INR", keyId);
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            log.error("Razorpay order error: {}", e.getMessage());
            throw ApiException.badRequest("Could not reach the payment service. Please try again.");
        }
    }

    /** Checks Razorpay's signature (HMAC SHA256 of orderId|paymentId) and marks the fee paid. */
    @Transactional
    public Msg verify(String studentId, Long feeId, String orderId, String paymentId, String signature) {
        if (!onlineEnabled()) throw ApiException.badRequest("Online payment is not set up.");
        FeePayment p = payments.findByRazorpayOrderIdAndStudentId(orderId, studentId)
            .orElseThrow(() -> ApiException.badRequest("Payment not found."));
        if (!p.getFeeItemId().equals(feeId)) throw ApiException.badRequest("Payment does not match this fee.");
        if ("PAID".equals(p.getStatus())) return new Msg("Payment already recorded.");
        String expected = hmac(orderId + "|" + paymentId);
        if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), String.valueOf(signature).getBytes(StandardCharsets.UTF_8))) {
            throw ApiException.badRequest("Payment could not be verified.");
        }
        p.setRazorpayPaymentId(paymentId);
        p.setStatus("PAID");
        p.setPaidAt(Instant.now());
        payments.save(p);
        receipt(p, "Online (Razorpay)");
        return new Msg("Payment successful. Thank you!");
    }

    private String hmac(String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(keySecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** The student scanned the UPI QR, paid, and now sends the 12-digit UPI transaction ID for faculty to approve. */
    @Transactional
    public Msg submitQr(String studentId, Long feeId, String utr) {
        FeeItem f = feeFor(studentId, feeId);
        ensureNotPaid(studentId, feeId);
        String u = utr == null ? "" : utr.trim();
        if (!u.matches("\\d{12}")) throw ApiException.badRequest("Enter the 12-digit UPI transaction ID (UTR).");
        boolean waiting = payments.findByStudentIdOrderByIdDesc(studentId).stream()
            .anyMatch(p -> p.getFeeItemId().equals(feeId) && "PENDING".equals(p.getStatus()) && "QR".equals(p.getMethod()));
        if (waiting) throw ApiException.badRequest("Your payment is already waiting for approval.");
        if (payments.existsByUtrIgnoreCase(u)) throw ApiException.badRequest("This transaction ID was already submitted.");
        FeePayment p = new FeePayment();
        p.setFeeItemId(feeId);
        p.setStudentId(studentId);
        p.setAmount(f.getAmount());
        p.setMethod("QR");
        p.setStatus("PENDING");
        p.setUtr(u);
        payments.save(p);
        return new Msg("Submitted. Faculty will confirm your payment soon.");
    }

    // =====================  FACULTY  =====================

    @Transactional(readOnly = true)
    public List<FacultyFeeRow> forFaculty() {
        List<FacultyFeeRow> out = new ArrayList<>();
        for (FeeItem f : items.findAllByOrderByDueDateAscIdAsc()) {
            int total = f.getSectionId() == null ? (int) students.count() : (int) students.countBySectionId(f.getSectionId());
            List<FeePayment> ps = payments.findByFeeItemId(f.getId());
            int paid = (int) ps.stream().filter(p -> "PAID".equals(p.getStatus())).map(FeePayment::getStudentId).distinct().count();
            int pending = (int) ps.stream().filter(p -> "PENDING".equals(p.getStatus()) && "QR".equals(p.getMethod())).count();
            out.add(new FacultyFeeRow(f.getId(), f.getTitle(), f.getAmount(), String.valueOf(f.getDueDate()), f.getSectionId(), total, paid, pending));
        }
        return out;
    }

    @Transactional
    public FacultyFeeRow create(String facultyId, String title, long amount, LocalDate due, String sectionId) {
        if (amount < 1 || amount > 10_000_000) throw ApiException.badRequest("Enter an amount between 1 and 1,00,00,000.");
        FeeItem f = new FeeItem();
        f.setTitle(title.trim());
        f.setAmount(amount);
        f.setDueDate(due);
        f.setSectionId(sectionId == null || sectionId.isBlank() ? null : sectionId.trim());
        f.setCreatedBy(facultyId);
        items.save(f);
        int total = f.getSectionId() == null ? (int) students.count() : (int) students.countBySectionId(f.getSectionId());
        return new FacultyFeeRow(f.getId(), f.getTitle(), f.getAmount(), String.valueOf(f.getDueDate()), f.getSectionId(), total, 0, 0);
    }

    @Transactional(readOnly = true)
    public List<PendingRow> pending() {
        List<PendingRow> out = new ArrayList<>();
        for (FeePayment p : payments.findByStatusOrderByIdAsc("PENDING")) {
            if (!"QR".equals(p.getMethod())) continue;
            String name = students.findById(p.getStudentId()).map(Student::getName).orElse("");
            String title = items.findById(p.getFeeItemId()).map(FeeItem::getTitle).orElse("");
            out.add(new PendingRow(p.getId(), p.getStudentId(), name, title, p.getAmount(), p.getUtr(), String.valueOf(p.getCreatedAt())));
        }
        return out;
    }

    @Transactional
    public Msg decide(String facultyId, Long paymentId, boolean approve) {
        FeePayment p = payments.findById(paymentId).orElseThrow(() -> ApiException.notFound("Payment"));
        if (!"PENDING".equals(p.getStatus())) throw ApiException.badRequest("This payment was already decided.");
        p.setStatus(approve ? "PAID" : "REJECTED");
        p.setDecidedBy(facultyId);
        if (approve) p.setPaidAt(Instant.now());
        payments.save(p);
        if (approve) receipt(p, "UPI QR (approved by faculty)");
        return new Msg(approve ? "Payment approved." : "Payment rejected.");
    }

    private void receipt(FeePayment p, String how) {
        try {
            Student s = students.findById(p.getStudentId()).orElse(null);
            FeeItem f = items.findById(p.getFeeItemId()).orElse(null);
            if (s == null || f == null || s.getEmail() == null || s.getEmail().isBlank()) return;
            mail.send(s.getEmail(), "SAMS fee receipt",
                "Hello " + s.getName() + ",\n\nWe received your payment.\n\nFee: " + f.getTitle()
                + "\nAmount: Rs. " + p.getAmount() + "\nMethod: " + how
                + "\nReference: " + (p.getRazorpayPaymentId() != null ? p.getRazorpayPaymentId() : p.getUtr())
                + "\n\nThank you.");
        } catch (Exception e) {
            log.error("Could not send receipt: {}", e.getMessage());
        }
    }
}
