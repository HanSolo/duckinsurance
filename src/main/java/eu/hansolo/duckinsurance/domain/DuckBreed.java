package eu.hansolo.duckinsurance.domain;

import java.math.BigDecimal;

public enum DuckBreed {

    MALLARD("Mallard", "1.00"),
    PEKIN("Pekin", "0.95"),
    MUSCOVY("Muscovy", "1.15"),
    CAYUGA("Cayuga", "1.05"),
    RUNNER("Indian Runner", "1.10"),
    MANDARIN("Mandarin", "1.25");

    private final String     label;
    private final BigDecimal riskFactor;


    DuckBreed(String label, String riskFactor) {
        this.label = label;
        this.riskFactor = new BigDecimal(riskFactor);
    }


    public String getLabel() {
        return label;
    }

    /** Some breeds get into more trouble than others. */
    public BigDecimal getRiskFactor() {
        return riskFactor;
    }
}
