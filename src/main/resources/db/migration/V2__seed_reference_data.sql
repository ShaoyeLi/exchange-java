INSERT INTO assets (id, symbol, decimal_scale) VALUES
    (1, 'BTC', 6),
    (2, 'USDT', 8);

INSERT INTO markets (
    id, symbol, base_asset_id, quote_asset_id, fee_wallet_user_id,
    price_scale, quantity_scale, price_tick, quantity_step,
    minimum_quantity, minimum_notional, maker_fee_bps, taker_fee_bps,
    last_arrival_sequence, last_execution_sequence
) VALUES (
    1, 'BTC/USDT', 1, 2, 0,
    2, 6, 1, 1,
    100, 1000000, 10, 20,
    0, 0
);

INSERT INTO wallets (user_id, asset_id, available, locked) VALUES
    (0, 1, 0, 0),
    (0, 2, 0, 0);

