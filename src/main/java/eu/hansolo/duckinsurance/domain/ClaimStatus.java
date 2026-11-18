package eu.hansolo.duckinsurance.domain;

public enum ClaimStatus {
    SUBMITTED, UNDER_REVIEW, APPROVED, REJECTED;

    public boolean isOpen() {
        return this == SUBMITTED || this == UNDER_REVIEW;
    }
}
