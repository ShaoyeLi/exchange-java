const baseUrl = process.env.BASE_URL ?? 'http://localhost:8080'

async function request(path, options = {}) {
  const response = await fetch(`${baseUrl}${path}`, options)
  const body = await response.json().catch(() => null)
  if (!response.ok) throw new Error(`${options.method ?? 'GET'} ${path}: ${response.status} ${JSON.stringify(body)}`)
  return { response, body }
}

const login = (email, password) => request('/api/auth/login', {
  method: 'POST', headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({ email, password }),
})

const { body: session } = await login(
  process.env.TRADER_EMAIL ?? 'trader@example.com',
  process.env.TRADER_PASSWORD ?? 'Trader123!',
)
const auth = { Authorization: `Bearer ${session.accessToken}`, 'Content-Type': 'application/json' }
const before = (await request('/api/account/balances', { headers: auth })).body
const beforeBtc = before.find(balance => balance.asset === 'BTC')

const orderKey = crypto.randomUUID()
const { body: order } = await request('/api/orders', {
  method: 'POST', headers: { ...auth, 'Idempotency-Key': orderKey },
  body: JSON.stringify({
    market: 'BTC/USDT', side: 'SELL', type: 'LIMIT', timeInForce: 'GTC',
    price: 999999, protectionPrice: null, quantity: 100,
  }),
})
const { body: replay } = await request('/api/orders', {
  method: 'POST', headers: { ...auth, 'Idempotency-Key': orderKey },
  body: JSON.stringify({
    market: 'BTC/USDT', side: 'SELL', type: 'LIMIT', timeInForce: 'GTC',
    price: 999999, protectionPrice: null, quantity: 100,
  }),
})
if (replay.orderId !== order.orderId || replay.replayed !== true) throw new Error('order replay was not idempotent')

await request(`/api/orders/${order.orderId}/cancel`, {
  method: 'POST', headers: { ...auth, 'Idempotency-Key': crypto.randomUUID() },
  body: JSON.stringify({ market: 'BTC/USDT' }),
})
const after = (await request('/api/account/balances', { headers: auth })).body
const afterBtc = after.find(balance => balance.asset === 'BTC')
if (!beforeBtc || !afterBtc || beforeBtc.available !== afterBtc.available || beforeBtc.locked !== afterBtc.locked) {
  throw new Error('place/cancel did not restore the BTC wallet')
}

console.log(JSON.stringify({
  status: 'PASS', orderId: order.orderId, replayed: replay.replayed,
  walletRestored: true, requestIdHeader: (await request('/livez')).response.headers.get('x-request-id'),
}, null, 2))
