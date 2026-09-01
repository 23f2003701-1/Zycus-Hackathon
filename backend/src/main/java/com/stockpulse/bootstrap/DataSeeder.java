package com.stockpulse.bootstrap;

import java.math.BigDecimal;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.stockpulse.domain.Category;
import com.stockpulse.domain.Product;
import com.stockpulse.domain.TriggerReason;
import com.stockpulse.repository.ProductRepository;
import com.stockpulse.service.SuggestionService;

/**
 * Seeds the 8 demo products from Addendum A.
 *
 * <p>Deliberately Java rather than {@code data.sql}: an SQL seed has to name Hibernate's generated
 * columns (including {@code version} and the audit timestamps) and has to run after schema
 * creation, so it breaks whenever an entity changes and fails the whole startup when ordering
 * slips. Going through the repository means the seed is checked by the compiler and constructs
 * products through the same constructor the API uses.
 *
 * <p>Two products are positioned for the demo:
 * <ul>
 *   <li>PRD-003 - stock 8 against a threshold of 15, already low, for the inventory-low path</li>
 *   <li>PRD-008 - velocity 15 in APPAREL, for the demand-spike path</li>
 * </ul>
 */
@Component
public class DataSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DataSeeder.class);

    private final ProductRepository products;
    private final SuggestionService suggestions;

    public DataSeeder(ProductRepository products, SuggestionService suggestions) {
        this.products = products;
        this.suggestions = suggestions;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (products.count() > 0) {
            log.info("Catalog already populated ({} products); skipping seed", products.count());
            return;
        }

        products.saveAll(List.of(
                seed("PRD-001", "SKU-ELEC-001", "Wireless Earbuds Pro", Category.ELECTRONICS,
                        "79.99", 45, 20, 3, "42.00", "49.99", "SUP-ELEC-01"),
                seed("PRD-002", "SKU-ELEC-002", "USB-C Hub 7-Port", Category.ELECTRONICS,
                        "34.99", 120, 30, 1, "18.50", "22.99", "SUP-ELEC-01"),
                seed("PRD-003", "SKU-APP-001", "Organic Cotton T-Shirt", Category.APPAREL,
                        "24.99", 8, 15, 12, "11.00", "14.99", "SUP-APP-01"),
                seed("PRD-004", "SKU-APP-002", "Running Shorts - Navy", Category.APPAREL,
                        "39.99", 55, 20, 2, "19.00", "24.99", "SUP-APP-01"),
                seed("PRD-005", "SKU-HOME-001", "Ceramic Pour-Over Set", Category.HOME,
                        "49.99", 22, 10, 4, "26.00", "32.99", "SUP-HOME-01"),
                seed("PRD-006", "SKU-HOME-002", "LED Desk Lamp - Dimmable", Category.HOME,
                        "59.99", 0, 15, 0, "31.00", "38.99", "SUP-HOME-01"),
                seed("PRD-007", "SKU-ELEC-003", "Portable Charger 20K", Category.ELECTRONICS,
                        "44.99", 18, 25, 8, "23.50", "29.99", "SUP-ELEC-02"),
                seed("PRD-008", "SKU-APP-003", "Hoodie - Heather Grey", Category.APPAREL,
                        "54.99", 11, 12, 15, "27.00", "34.99", "SUP-APP-02")));

        seedInitialReview("PRD-003");

        log.info("Seeded {} demo products; PRD-003 is below threshold and PRD-008 is primed for a spike",
                products.count());
    }

    /**
     * Addendum A seeds this product as PRICE_REVIEW_PENDING. Rather than force the status field,
     * the seeder raises a real INITIAL suggestion, so the status is a consequence of an outstanding
     * pricing question rather than a value that contradicts it - and the console has something to
     * act on the moment it loads.
     */
    private void seedInitialReview(String productId) {
        products.findById(productId).ifPresent(product ->
                suggestions.generatePricingIfAbsent(product, TriggerReason.INITIAL));
    }

    private static Product seed(String id, String sku, String name, Category category, String price,
                                int stock, int threshold, int velocity, String costPrice,
                                String marginFloor, String supplierId) {
        Product product = new Product(id, sku, name, category, new BigDecimal(price), stock, threshold);
        product.setDemandVelocity(velocity);
        product.setCostPrice(new BigDecimal(costPrice));
        product.setMarginFloor(new BigDecimal(marginFloor));
        product.setSupplierId(supplierId);
        return product;
    }
}
