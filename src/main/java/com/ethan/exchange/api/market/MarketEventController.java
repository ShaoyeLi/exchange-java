package com.ethan.exchange.api.market;

import com.ethan.exchange.messaging.MarketEventStore;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Locale;

@RestController
@RequestMapping("/api/public/markets/events")
public class MarketEventController {

    private final MarketEventStore store;

    public MarketEventController(MarketEventStore store) {
        this.store = store;
    }

    @GetMapping
    public MarketEventSnapshot snapshot(
            @RequestParam String market,
            @RequestParam(defaultValue = "0") long afterSequence,
            @RequestParam(defaultValue = "200") int limit) {
        String normalized = normalize(market);
        if (afterSequence < 0 || limit <= 0 || limit > 500) {
            throw new IllegalArgumentException("invalid snapshot range");
        }
        return new MarketEventSnapshot(normalized, store.lastSequence(normalized),
                store.load(normalized, afterSequence, limit));
    }

    private static String normalize(String market) {
        if (market == null || market.isBlank()) {
            throw new IllegalArgumentException("market is required");
        }
        return market.trim().toUpperCase(Locale.ROOT);
    }
}
