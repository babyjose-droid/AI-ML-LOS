package com.rhythm.los.application;

import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.time.LocalDate;

public record ApplicationRequest(
        @NotBlank String productCode,
        @NotBlank @Size(max = 120) String applicantName,
        @NotBlank @Pattern(regexp = "[A-Z]{3}P[A-Z][0-9]{4}[A-Z]", message = "must be a valid individual PAN, e.g. ABCPK1234L") String pan,
        @NotBlank @Pattern(regexp = "[6-9][0-9]{9}", message = "must be a 10-digit Indian mobile number") String mobile,
        @Email String email,
        @NotNull @Past LocalDate dob,
        String gender,
        @Size(max = 300) String address,
        @Size(max = 80) String city,
        @Pattern(regexp = "^$|[1-9][0-9]{5}", message = "must be a 6-digit PIN code") String pincode,
        @Size(max = 160) String businessName,
        @NotNull @DecimalMin("0") BigDecimal businessVintageYears,
        @NotNull @Positive BigDecimal declaredMonthlyIncome,
        @NotNull @DecimalMin("0") BigDecimal essentialExpenses,
        @NotNull @Positive BigDecimal loanAmount,
        @Min(1) @Max(360) int tenureMonths,
        @Size(max = 200) String purpose,
        @Pattern(regexp = "^$|[0-9]{9,18}", message = "must be 9-18 digits") String bankAccountNo,
        @Pattern(regexp = "^$|[A-Z]{4}0[A-Z0-9]{6}", message = "must be a valid IFSC") String bankIfsc,
        boolean consentBureau,
        boolean consentAa,
        boolean consentKyc) {}
