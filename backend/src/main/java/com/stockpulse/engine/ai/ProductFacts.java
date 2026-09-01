package com.stockpulse.engine.ai;

import java.math.BigDecimal;
import java.math.RoundingMode;

import com.stockpulse.domain.Product;
import com.stockpulse.engine.CommerceContext;

/**
 * The numeric fact sheet handed to the model.
 *
 * <p>Shared between the pricing and reorder prompts because it is the same underlying data - what
 * differs between prompts is the decision being framed around it, not the numbers. Derived values
 * (days of cover, margin, velocity ratio) are computed here rather than left to the model, since
 * arithmetic is the one thing an LLM should not be asked to do.
 */
final class ProductFacts {

    private ProductFacts() {
    }

    static String of(CommerceContext context) {
        Product product = context.product();
        StringBuilder facts = new StringBuilder("PRODUCT FACTS\n");

        line(facts, "Name", product.getName());
        line(facts, "Category", product.getCategory().name());
        line(facts, "Current price", "$" + product.getCurrentPrice().toPlainString());
        line(facts, "Unit cost", cost(product));
        line(facts, "Margin floor", product.getMarginFloor() == null
                ? "not set"
                : "$" + product.getMarginFloor().toPlainString() + " (never recommend below this)");
        line(facts, "Stock on hand", product.getStockLevel() + " units");
        line(facts, "Reorder threshold", threshold(product));
        line(facts, "Demand velocity", product.getDemandVelocity() + " orders in the last 24h");
        line(facts, "Category peer average", peers(context));
        line(facts, "Days of cover", cover(context));

        return facts.toString();
    }

    private static String cost(Product product) {
        if (product.getCostPrice() == null || product.getCostPrice().signum() <= 0) {
            return "not set";
        }
        BigDecimal margin = product.getCurrentPrice().subtract(product.getCostPrice());
        BigDecimal marginPct = margin
                .multiply(new BigDecimal("100"))
                .divide(product.getCurrentPrice(), 1, RoundingMode.HALF_UP);
        return "$%s (current gross margin %s%%)".formatted(
                product.getCostPrice().toPlainString(), marginPct.toPlainString());
    }

    private static String threshold(Product product) {
        int gap = product.getReorderThreshold() - product.getStockLevel();
        if (gap > 0) {
            return "%d units - BELOW THRESHOLD by %d units".formatted(product.getReorderThreshold(), gap);
        }
        return "%d units - stock is above threshold".formatted(product.getReorderThreshold());
    }

    private static String peers(CommerceContext context) {
        if (context.peerAverageDemandVelocity() <= 0) {
            return "no comparable products with measurable demand";
        }
        return "%.1f orders/24h - this product is running at %.1fx its peers".formatted(
                context.peerAverageDemandVelocity(), context.velocityRatio());
    }

    private static String cover(CommerceContext context) {
        double days = context.daysOfCover();
        if (days < 0) {
            return "no measurable demand, so cover is effectively unlimited";
        }
        return "%.1f days at the current sales rate".formatted(days);
    }

    private static void line(StringBuilder target, String label, String value) {
        target.append("  %-22s: %s%n".formatted(label, value));
    }
}
