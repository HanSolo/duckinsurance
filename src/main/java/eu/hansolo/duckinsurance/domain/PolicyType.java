package eu.hansolo.duckinsurance.domain;

import java.math.BigDecimal;

public enum PolicyType {

    VET_CARE("Vet care", "0.030"),
    LIFE("Duck life", "0.050"),
    LIABILITY("Pond liability", "0.004"),
    COOP("Coop & pond", "0.008");

    private final String     label;
    private final BigDecimal annualRate;


    PolicyType(String label, String annualRate) {
        this.label = label;
        this.annualRate = new BigDecimal(annualRate);
    }


    public String getLabel() {
        return label;
    }

    /** Share of the coverage amount charged per year before risk factors. */
    public BigDecimal getAnnualRate() {
        return annualRate;
    }
}
