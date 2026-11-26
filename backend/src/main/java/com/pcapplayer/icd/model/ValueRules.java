package com.pcapplayer.icd.model;

/**
 * How a decoded integer is turned into its real value and checked. Every part is optional (null).
 * From the legacy annotations {@code @FixedPoint}, {@code @ValidRange} and {@code @InvalidityValue}.
 *
 * @param lsb              the real value is the stored integer times this ({@code @FixedPoint(lsb = 0.1)})
 * @param minValue         smallest valid real value, checked after {@code lsb} is applied
 * @param maxValue         largest valid real value, checked after {@code lsb} is applied
 * @param invalidityValue  a stored integer that means "no valid value" ({@code @InvalidityValue(200)})
 */
public record ValueRules(Double lsb, Double minValue, Double maxValue, Long invalidityValue) {

    public boolean isEmpty() {
        return lsb == null && minValue == null && maxValue == null && invalidityValue == null;
    }
}
