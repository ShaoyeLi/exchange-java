package com.ethan.exchange.api.market;

import com.ethan.exchange.api.query.ExchangeQueryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Locale;

@RestController
@RequestMapping("/api/public/markets")
public class PublicMarketController {

    private final ExchangeQueryService queries;

    public PublicMarketController(ExchangeQueryService queries) {
        this.queries = queries;
    }

    @GetMapping("/config")
    public ExchangeQueryService.MarketView market(@RequestParam String market) {
        return queries.market(normalize(market));
    }

    @GetMapping("/book")
    public BookView book(
            @RequestParam String market,
            @RequestParam(defaultValue = "30") int limit) {
        String normalized = normalize(market);
        return new BookView(normalized, queries.book(normalized, "BUY", limit),
                queries.book(normalized, "SELL", limit));
    }

    @GetMapping("/trades")
    public List<ExchangeQueryService.TradeView> trades(
            @RequestParam String market,
            @RequestParam(defaultValue = "50") int limit) {
        return queries.publicTrades(normalize(market), limit);
    }

    private static String normalize(String market) {
        if (market == null || market.isBlank()) {
            throw new IllegalArgumentException("market is required");
        }
        return market.trim().toUpperCase(Locale.ROOT);
    }

    public record BookView(
            String market,
            List<ExchangeQueryService.BookLevelView> bids,
            List<ExchangeQueryService.BookLevelView> asks) {
    }
}
