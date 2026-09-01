package com.stockpulse.domain;

import java.math.BigDecimal;

public enum PriceDirection {

    INCREASE,
    DECREASE,
    HOLD;

    public static PriceDirection between(BigDecimal currentPrice, BigDecimal recommendedPrice) {
        int comparison = recommendedPrice.compareTo(currentPrice);
        if (comparison > 0) {
            return INCREASE;
        }
        return comparison < 0 ? DECREASE : HOLD;
    }
}
