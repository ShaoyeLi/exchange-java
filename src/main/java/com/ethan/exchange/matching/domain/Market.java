package com.ethan.exchange.matching.domain;

public record Market(
        MarketSymbol symbol,
        AssetSymbol baseAsset,
        AssetSymbol quoteAsset,
        int priceScale,
        int quantityScale,
        Price priceTick,
        Quantity quantityStep,
        Quantity minimumQuantity,
        Notional minimumNotional
) {

    public Market {
        if (symbol == null || baseAsset == null || quoteAsset == null
                || priceTick == null || quantityStep == null
                || minimumQuantity == null || minimumNotional == null) {
            throw new DomainException(DomainErrorCode.INVALID_MARKET,
                    "market fields must not be null");
        }
        if (baseAsset.equals(quoteAsset)) {
            throw new DomainException(DomainErrorCode.INVALID_MARKET,
                    "base and quote assets must differ");
        }
        MarketSymbol expected = MarketSymbol.of(baseAsset.value() + "/" + quoteAsset.value());
        if (!symbol.equals(expected)) {
            throw new DomainException(DomainErrorCode.INVALID_MARKET,
                    "market symbol must match base and quote assets");
        }
        if (priceScale < 0 || quantityScale < 0
                || priceTick.scale() != priceScale
                || quantityStep.scale() != quantityScale
                || minimumQuantity.scale() != quantityScale
                || minimumNotional.scale() != priceScale + quantityScale) {
            throw new DomainException(DomainErrorCode.INVALID_MARKET,
                    "market numeric scales are inconsistent");
        }
        if (quantityStep.isZero() || minimumQuantity.isZero()
                || minimumNotional.unscaled() == 0) {
            throw new DomainException(DomainErrorCode.INVALID_MARKET,
                    "market step and minimum values must be positive");
        }
        if (minimumQuantity.unscaled() % quantityStep.unscaled() != 0) {
            throw new DomainException(DomainErrorCode.INVALID_MARKET,
                    "minimum quantity must align with quantity step");
        }
    }

    public void validate(OrderRequest request) {
        if (!symbol.equals(request.market())) {
            throw new DomainException(DomainErrorCode.INVALID_MARKET,
                    "engine market " + symbol + " cannot accept " + request.market());
        }
        validateQuantity(request.quantity());
        Price effectivePrice = request.effectiveLimit();
        validatePrice(effectivePrice);
        if (effectivePrice.multiply(request.quantity()).compareTo(minimumNotional) < 0) {
            throw new DomainException(DomainErrorCode.INVALID_ORDER,
                    "order is below minimum notional");
        }
    }

    private void validatePrice(Price price) {
        if (price == null || price.scale() != priceScale
                || price.unscaled() % priceTick.unscaled() != 0) {
            throw new DomainException(DomainErrorCode.INVALID_ORDER,
                    "price scale or tick is invalid for " + symbol);
        }
    }

    private void validateQuantity(Quantity quantity) {
        if (quantity.scale() != quantityScale
                || quantity.unscaled() % quantityStep.unscaled() != 0
                || quantity.compareTo(minimumQuantity) < 0) {
            throw new DomainException(DomainErrorCode.INVALID_ORDER,
                    "quantity scale, step, or minimum is invalid for " + symbol);
        }
    }
}
