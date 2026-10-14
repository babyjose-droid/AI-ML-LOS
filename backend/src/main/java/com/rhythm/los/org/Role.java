package com.rhythm.los.org;

/** Internal roles. Sanction levels: CREDIT_OFFICER = L1, CREDIT_MANAGER = L2, CRO = L3. */
public enum Role {
    ADMIN, SALES, OPERATIONS, CREDIT_OFFICER, CREDIT_MANAGER, CRO, FRAUD_ANALYST, COMPLIANCE,
    /** Field visits, group formation (CGT) and vehicle valuation. */
    FIELD_OFFICER,
    /** Reads title, EC and legal flags on collateral (does not certify title: the lender's empanelled advocate does). */
    LEGAL_REVIEWER,
    /** Empanelled valuation desk: records and reviews valuations. */
    VALUER,
    /** A customer signed in to the borrower portal with mobile OTP. Never a staff user. */
    BORROWER;

    public int sanctionLevel() {
        return switch (this) {
            case CREDIT_OFFICER -> 1;
            case CREDIT_MANAGER -> 2;
            case CRO -> 3;
            default -> 0;
        };
    }
}
