package eu.hansolo.duckinsurance.service;

import eu.hansolo.duckinsurance.domain.Duck;
import eu.hansolo.duckinsurance.domain.PolicyType;
import jakarta.enterprise.context.ApplicationScoped;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Pure business logic: a plain CDI bean, trivially unit-testable. The premium depends on the duck, not the owner. */
@ApplicationScoped
public class PremiumCalculator {

    public BigDecimal annualPremium(PolicyType type, BigDecimal coverage, Duck duck) {
        return coverage.multiply(type.getAnnualRate()).multiply(duck.getBreed().getRiskFactor()).multiply(ageFactor(duck.getAgeYears())).setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal ageFactor(int years) {
        if (years < 1) return new BigDecimal("1.40");
        if (years < 5) return new BigDecimal("1.00");
        if (years < 8) return new BigDecimal("1.30");
        return new BigDecimal("1.60");
    }
}
