package com.rhythm.los.org;

/** Internal roles. Sanction levels: CREDIT_OFFICER = L1, CREDIT_MANAGER = L2, CRO = L3. */
public enum Role {
    ADMIN, SALES, OPERATIONS, CREDIT_OFFICER, CREDIT_MANAGER, CRO, FRAUD_ANALYST, COMPLIANCE;

    public int sanctionLevel() {
        return switch (this) {
            case CREDIT_OFFICER -> 1;
            case CREDIT_MANAGER -> 2;
            case CRO -> 3;
            default -> 0;
        };
    }
}
