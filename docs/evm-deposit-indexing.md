# EVM Deposit Indexing

## Two state machines

An ERC-20 `Transfer` log is evidence that a chain transaction emitted an event.
It is not yet an internal balance. The indexer deliberately separates these states:

```text
chain:     observed block -> enough confirmations -> still canonical
deposit:   SEEN -> CREDITED
             |        |
             v        v
          ORPHANED   BLOCKED/manual reconciliation on a deep reorg
```

The scanner stores block hashes, decoded logs, and its next-block cursor before
crediting. Only deposits at or below `head - confirmationDepth` are eligible.
The credit transaction atomically updates the wallet, appends a ledger entry,
links the deposit to that journal, and marks it `CREDITED`.

## Identity and attribution

- Chain identity is `(chainId, transactionHash, logIndex)`. Transaction hash
  alone is insufficient because one transaction can emit multiple transfers.
- The token contract address selects the internal asset and decimal conversion.
- The indexed `to` topic must match an enabled deposit address mapped to one user.
- Addresses and hashes are normalized lowercase hexadecimal before persistence.
- Raw `uint256` amounts remain decimal integers. Conversion to internal fixed-point
  units must be exact and fit a signed Java `long`; otherwise the row is `REVIEW`
  and no money moves.

## Scan and reorganization boundary

Each lease-owned cycle fetches at most `scanBatchSize` blocks. Parent hashes are
checked in memory and each returned log's block hash must match the fetched block.
The block rows, deposits, and cursor then commit together.

Before advancing, the scanner compares its last persisted block hash with the
node's canonical block at that height. A shallow reorg containing only uncredited
deposits marks those rows `ORPHANED`, removes replaced block headers, and rewinds
the cursor. If an affected deposit was already credited, the network is marked
`BLOCKED`; this implementation requires custody reconciliation and an explicit
operator decision instead of silently debiting a user.

## Accounting boundary

An internal trade journal is zero-sum between internal wallets. A deposit crosses
the external/internal boundary: the chain event is the source evidence and one
customer-liability balance is issued internally. The credit transaction therefore writes one
`DEPOSIT_CREDIT` wallet posting linked to the unique chain event. Production
custody asset accounting and hot/cold-wallet reconciliation remain external
operational controls, not an invented counterparty wallet with a synthetic balance.

## Verification

`EvmDepositIndexingIT` covers cursor advancement, confirmation-gated crediting,
duplicate events, RPC failures, mismatched block hashes, wrong chain IDs, shallow
reorganizations, and blocking when a reorganization reaches a credited deposit.

## References

- [Ethereum JSON-RPC `eth_getLogs`](https://ethereum.org/developers/docs/apis/json-rpc/)
- [ERC-20 `Transfer` event](https://eips.ethereum.org/EIPS/eip-20)
- [EIP-234 block-hash log filtering and reorg rationale](https://eips.ethereum.org/EIPS/eip-234)
- [Web3j Ethereum log queries](https://docs.web3j.io/latest/transactions/ethereum_logs/)
