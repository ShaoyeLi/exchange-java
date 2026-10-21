const baseUrl = process.env.BASE_URL ?? 'http://localhost:8080'
const virtualUsers = Number(process.env.LOAD_VUS ?? 8)
const durationSeconds = Number(process.env.LOAD_DURATION_SECONDS ?? 10)
const mode = process.env.LOAD_MODE ?? 'read'

if (!Number.isInteger(virtualUsers) || virtualUsers < 1 || virtualUsers > 100) throw new Error('LOAD_VUS must be 1..100')
if (!Number.isFinite(durationSeconds) || durationSeconds < 1 || durationSeconds > 300) throw new Error('LOAD_DURATION_SECONDS must be 1..300')
if (!['read', 'write'].includes(mode)) throw new Error('LOAD_MODE must be read or write')

let token = null
if (mode === 'write') {
  const response = await fetch(`${baseUrl}/api/auth/login`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      email: process.env.TRADER_EMAIL ?? 'trader@example.com',
      password: process.env.TRADER_PASSWORD ?? 'Trader123!',
    }),
  })
  if (!response.ok) throw new Error(`login failed: ${response.status}`)
  token = (await response.json()).accessToken
}

const latencies = []
let successes = 0
let failures = 0
const deadline = performance.now() + durationSeconds * 1000

async function checkedFetch(path, options) {
  const started = performance.now()
  const response = await fetch(`${baseUrl}${path}`, options)
  const elapsed = performance.now() - started
  if (!response.ok) throw new Error(`${response.status}: ${await response.text()}`)
  return { elapsed, body: await response.json() }
}

async function readCycle() {
  return checkedFetch('/api/public/markets/book?market=BTC%2FUSDT&limit=20')
}

async function writeCycle() {
  const headers = {
    Authorization: `Bearer ${token}`, 'Content-Type': 'application/json',
    'Idempotency-Key': crypto.randomUUID(),
  }
  const placed = await checkedFetch('/api/orders', {
    method: 'POST', headers,
    body: JSON.stringify({
      market: 'BTC/USDT', side: 'SELL', type: 'LIMIT', timeInForce: 'GTC',
      price: 999999, protectionPrice: null, quantity: 100,
    }),
  })
  const canceled = await checkedFetch(`/api/orders/${placed.body.orderId}/cancel`, {
    method: 'POST', headers: { ...headers, 'Idempotency-Key': crypto.randomUUID() },
    body: JSON.stringify({ market: 'BTC/USDT' }),
  })
  return { elapsed: placed.elapsed + canceled.elapsed }
}

async function worker() {
  while (performance.now() < deadline) {
    try {
      const result = mode === 'read' ? await readCycle() : await writeCycle()
      latencies.push(result.elapsed)
      successes++
    } catch (error) {
      failures++
      if (failures <= 3) console.error(String(error))
    }
  }
}

const started = performance.now()
await Promise.all(Array.from({ length: virtualUsers }, worker))
const elapsedSeconds = (performance.now() - started) / 1000
latencies.sort((a, b) => a - b)
const percentile = value => latencies.length ? latencies[Math.min(latencies.length - 1, Math.ceil(latencies.length * value) - 1)] : null

console.log(JSON.stringify({
  mode, virtualUsers, durationSeconds, completed: successes, failures,
  requestsPerSecond: Number((successes / elapsedSeconds).toFixed(2)),
  latencyMs: {
    p50: percentile(.5) === null ? null : Number(percentile(.5).toFixed(2)),
    p95: percentile(.95) === null ? null : Number(percentile(.95).toFixed(2)),
    p99: percentile(.99) === null ? null : Number(percentile(.99).toFixed(2)),
  },
}, null, 2))

if (failures > 0) process.exitCode = 1
