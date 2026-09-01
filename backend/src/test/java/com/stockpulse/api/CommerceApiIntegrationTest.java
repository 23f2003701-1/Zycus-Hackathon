package com.stockpulse.api;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Exercises the full chain - controller to service to engine to repository - over real HTTP
 * semantics. Dirties the context because it mutates the seeded catalog.
 */
@SpringBootTest(properties = {
        // Pin the deterministic advisor and switch off background writes so these assertions
        // describe the API alone. The agentic loop has its own dedicated test.
        "commerce.active-strategy=ruleBased",
        "commerce.agentic-loop-enabled=false"
})
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class CommerceApiIntegrationTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper json;

    @Test
    void catalogIsListableAndFilterable() throws Exception {
        mvc.perform(get("/products"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(greaterThan(0))));

        mvc.perform(get("/products").param("category", "APPAREL"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].category").value("APPAREL"));

        mvc.perform(get("/products").param("status", "OUT_OF_STOCK"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("PRD-006"));
    }

    @Test
    void unknownProductYieldsNotFound() throws Exception {
        mvc.perform(get("/products/PRD-NOPE"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail", containsString("not found")));
    }

    /**
     * The stream is validated before the response commits, so an unknown product is a normal 404
     * rather than an error event delivered inside a 200 event stream.
     */
    @Test
    void theStreamingEndpointRejectsAnUnknownProductBeforeItStartsStreaming() throws Exception {
        mvc.perform(post("/products/PRD-NOPE/suggest-pricing/stream"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail", containsString("not found")));
    }

    @Test
    void theStreamingEndpointOpensAnEventStreamForAKnownProduct() throws Exception {
        mvc.perform(post("/products/PRD-001/suggest-pricing/stream"))
                .andExpect(status().isOk())
                .andExpect(request().asyncStarted())
                .andExpect(header().string("Content-Type", containsString(MediaType.TEXT_EVENT_STREAM_VALUE)));
    }

    @Test
    void createValidatesTheRequestBody() throws Exception {
        mvc.perform(post("/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"sku":"","name":"","category":"HOME","currentPrice":-1,
                                 "stockLevel":-5,"reorderThreshold":0}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void orderingBeyondAvailableStockIsRejected() throws Exception {
        // PRD-006 is seeded at zero stock
        mvc.perform(post("/products/PRD-006/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantity\":1}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail", containsString("insufficient stock")));
    }

    @Test
    void simulatedSaleDrainsStockAndRaisesVelocity() throws Exception {
        JsonNode before = getProduct("PRD-001");

        mvc.perform(post("/products/PRD-001/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantity\":2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stockLevel").value(before.get("stockLevel").asInt() - 2))
                .andExpect(jsonPath("$.demandVelocity").value(before.get("demandVelocity").asInt() + 2));
    }

    @Test
    void stockPatchDrivesTheOutOfStockTransition() throws Exception {
        mvc.perform(patch("/products/PRD-002/stock")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"stockLevel\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OUT_OF_STOCK"));

        mvc.perform(patch("/products/PRD-002/stock")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"stockLevel\":80}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    /** The core loop: a low-stock product gets a suggestion, and accepting it moves the price. */
    @Test
    void acceptingAPricingSuggestionIsTheOnlyThingThatMovesThePrice() throws Exception {
        // PRD-007 is seeded at stock 18 against a threshold of 25, so the low-stock rule applies
        JsonNode suggestion = json.readTree(mvc.perform(post("/products/PRD-007/suggest-pricing"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.triggerReason").value("MANUAL"))
                .andExpect(jsonPath("$.autoTriggered").value(false))
                .andExpect(jsonPath("$.direction").value("INCREASE"))
                .andExpect(jsonPath("$.generatedBy").value("ruleBased"))
                .andReturn().getResponse().getContentAsString());

        // Generating a suggestion puts the product into review but leaves the price alone
        mvc.perform(get("/products/PRD-007"))
                .andExpect(jsonPath("$.status").value("PRICE_REVIEW_PENDING"))
                .andExpect(jsonPath("$.currentPrice").value(44.99));

        long id = suggestion.get("id").asLong();
        double recommended = suggestion.get("recommendedPrice").asDouble();

        mvc.perform(patch("/pricing-suggestions/" + id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"ACCEPTED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACCEPTED"));

        mvc.perform(get("/products/PRD-007"))
                .andExpect(jsonPath("$.currentPrice").value(recommended))
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    void rejectingAPricingSuggestionLeavesThePriceUntouched() throws Exception {
        JsonNode before = getProduct("PRD-004");
        JsonNode suggestion = json.readTree(mvc.perform(post("/products/PRD-004/suggest-pricing"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());

        mvc.perform(patch("/pricing-suggestions/" + suggestion.get("id").asLong())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"REJECTED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"));

        mvc.perform(get("/products/PRD-004"))
                .andExpect(jsonPath("$.currentPrice").value(before.get("currentPrice").asDouble()))
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    void acceptingAReorderSuggestionReceivesStock() throws Exception {
        JsonNode before = getProduct("PRD-005");
        JsonNode suggestion = json.readTree(mvc.perform(post("/products/PRD-005/suggest-reorder"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recommendedQuantity").value(greaterThan(0)))
                .andReturn().getResponse().getContentAsString());

        int quantity = suggestion.get("recommendedQuantity").asInt();

        mvc.perform(patch("/reorder-suggestions/" + suggestion.get("id").asLong())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"ACCEPTED\"}"))
                .andExpect(status().isOk());

        mvc.perform(get("/products/PRD-005"))
                .andExpect(jsonPath("$.stockLevel").value(before.get("stockLevel").asInt() + quantity));
    }

    @Test
    void aDecidedSuggestionCannotBeDecidedAgain() throws Exception {
        JsonNode suggestion = json.readTree(mvc.perform(post("/products/PRD-008/suggest-reorder"))
                .andReturn().getResponse().getContentAsString());
        long id = suggestion.get("id").asLong();

        mvc.perform(patch("/reorder-suggestions/" + id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"ACCEPTED\"}"))
                .andExpect(status().isOk());

        mvc.perform(patch("/reorder-suggestions/" + id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"REJECTED\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void decisionMustBeATerminalStatus() throws Exception {
        JsonNode suggestion = json.readTree(mvc.perform(post("/products/PRD-003/suggest-reorder"))
                .andReturn().getResponse().getContentAsString());

        mvc.perform(patch("/reorder-suggestions/" + suggestion.get("id").asLong())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"PENDING\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void strategyCanBeSwitchedAtRuntimeOverHttp() throws Exception {
        mvc.perform(get("/admin/commerce-strategy"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activeStrategy").value("ruleBased"))
                .andExpect(jsonPath("$.availableStrategies").isArray());

        mvc.perform(patch("/admin/commerce-strategy")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"activeStrategy\":\"doesNotExist\"}"))
                .andExpect(status().isBadRequest());

        // Switching to the one registered strategy is a no-op that proves the path works
        mvc.perform(patch("/admin/commerce-strategy")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"activeStrategy\":\"ruleBased\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activeStrategy").value("ruleBased"));
    }

    @Test
    void createdProductIsRetrievableAndRejectsDuplicateSku() throws Exception {
        String body = """
                {"sku":"SKU-TEST-001","name":"Integration Widget","category":"HOME",
                 "currentPrice":19.99,"stockLevel":40,"reorderThreshold":10,
                 "costPrice":9.00,"supplierId":"SUP-TEST"}
                """;

        JsonNode created = json.readTree(mvc.perform(post("/products")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.supplierId").value("SUP-TEST"))
                .andReturn().getResponse().getContentAsString());

        mvc.perform(get("/products/" + created.get("id").asText()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sku").value("SKU-TEST-001"));

        mvc.perform(post("/products").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
    }

    private JsonNode getProduct(String id) throws Exception {
        return json.readTree(mvc.perform(get("/products/" + id))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }
}
