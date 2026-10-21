export type Session = {
  accessToken: string
  expiresAt: string
  userId: number
  email: string
  role: 'TRADER' | 'ADMIN'
}

export type Balance = { asset: string; scale: number; available: number; locked: number }
export type Order = {
  id: number; market: string; side: string; type: string; timeInForce: string
  status: string; price: number | null; protectionPrice: number | null
  originalQuantity: number; remainingQuantity: number; createdAt: string
}
export type Trade = {
  id: number; market: string; executionSequence: number; makerOrderId: number
  takerOrderId: number; price: number; quantity: number; quoteQuantity: number; createdAt: string
}
export type Deposit = {
  id: number; chainId: number; asset: string; scale: number; transactionHash: string; logIndex: number
  blockNumber: number; rawAmount: string; internalAmount: number | null; status: string
  observedAt: string; creditedAt: string | null
}
export type Withdrawal = {
  id: number; asset: string; scale: number; destinationAddress: string; amount: number
  status: string; createdAt: string
}
export type Market = {
  symbol: string; baseAsset: string; quoteAsset: string; priceScale: number
  quantityScale: number; priceTick: number; quantityStep: number
  minimumQuantity: number; minimumNotional: number; makerFeeBps: number; takerFeeBps: number
}
export type BookLevel = { price: number; quantity: number; orderCount: number }
export type Book = { market: string; bids: BookLevel[]; asks: BookLevel[] }
export type Operations = {
  pendingOutbox: number; deadOutbox: number; depositsInReview: number
  withdrawalsInReview: number
  chains: { chainId: number; name: string; nextBlock: number; status: string; blockedReason: string | null }[]
}
