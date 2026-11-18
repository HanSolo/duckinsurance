package eu.hansolo.duckinsurance.rest;

import eu.hansolo.duckinsurance.domain.PolicyType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Request bodies as records, validated with Bean Validation. */
public final class Requests {

    private Requests() {
    }


    public record Quote(@NotNull Long duckId, @NotNull PolicyType type, @NotNull @Positive BigDecimal coverageAmount) {
    }

    public record IssuePolicy(@NotNull Long duckId, @NotNull PolicyType type, @NotNull @Positive BigDecimal coverageAmount, LocalDate startDate) {
    }

    public record SubmitClaim(@NotNull Long policyId, Long vetId, @NotBlank @Size(min = 10, max = 500) String description, @NotNull @Positive BigDecimal amount, @NotNull @PastOrPresent LocalDate incidentDate) {
    }

    public record Decision(boolean approve, String note) {
    }
}
