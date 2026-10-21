import { Client } from '@stomp/stompjs'
import {
  Activity, ArrowDownToLine, ArrowUpFromLine, BarChart3, BookOpen,
  CircleDollarSign, LogOut, RefreshCw, ServerCog, ShieldCheck, WalletCards,
  X, Zap,
} from 'lucide-react'
import type { LucideIcon } from 'lucide-react'
import { type FormEvent, useCallback, useEffect, useMemo, useState } from 'react'
import { request, websocketUrl } from './api'
import type {
  Balance, Book, Deposit, Market, Operations, Order, Session, Trade, Withdrawal,
} from './types'

const MARKET = 'BTC/USDT'

type View = 'trade' | 'portfolio' | 'activity' | 'operations'

export default function App() {
  const [session, setSession] = useState<Session | null>(() => {
    const raw = localStorage.getItem('exchange-session')
    if (!raw) return null
    try {
      const parsed = JSON.parse(raw) as Session
      return new Date(parsed.expiresAt).getTime() > Date.now() ? parsed : null
    } catch {
      localStorage.removeItem('exchange-session')
      return null
    }
  })

  const saveSession = (next: Session | null) => {
    setSession(next)
    if (next) localStorage.setItem('exchange-session', JSON.stringify(next))
    else localStorage.removeItem('exchange-session')
  }

  if (!session) return <Login onLogin={saveSession} />
  return <Terminal session={session} onLogout={() => saveSession(null)} />
}

function Login({ onLogin }: { onLogin: (session: Session) => void }) {
  const [email, setEmail] = useState('trader@example.com')
  const [password, setPassword] = useState('Trader123!')
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)

  const submit = async (event: FormEvent) => {
    event.preventDefault()
    setBusy(true)
    setError('')
    try {
      onLogin(await request<Session>('/api/auth/login', null, {
        method: 'POST', body: JSON.stringify({ email, password }),
      }))
    } catch (reason) {
      setError(message(reason))
    } finally {
      setBusy(false)
    }
  }

  return (
    <main className="login-shell">
      <section className="login-panel" aria-labelledby="login-title">
        <div className="brand brand-large"><Zap size={22} /> AXIOM</div>
        <div className="login-heading">
          <span className="eyebrow">Spot exchange console</span>
          <h1 id="login-title">Trading access</h1>
        </div>
        <form onSubmit={submit} className="stack-form">
          <label>Email<input value={email} onChange={event => setEmail(event.target.value)} type="email" required /></label>
          <label>Password<input value={password} onChange={event => setPassword(event.target.value)} type="password" required /></label>
          {error && <div className="form-error">{error}</div>}
          <button className="primary-button" disabled={busy}>{busy ? 'Authenticating...' : 'Sign in'}</button>
        </form>
        <div className="security-note"><ShieldCheck size={15} /> JWT session · role-scoped access</div>
      </section>
      <aside className="login-market">
        <div><span>BTC / USDT</span><strong>Live spot market</strong></div>
        <div className="market-bars" aria-hidden="true">
          {[38, 54, 33, 66, 48, 79, 61, 88, 72, 92, 67, 84].map((height, index) =>
            <i key={index} style={{ height: `${height}%` }} />)}
        </div>
      </aside>
    </main>
  )
}

function Terminal({ session, onLogout }: { session: Session; onLogout: () => void }) {
  const [view, setView] = useState<View>('trade')
  const [market, setMarket] = useState<Market | null>(null)
  const [book, setBook] = useState<Book>({ market: MARKET, bids: [], asks: [] })
  const [publicTrades, setPublicTrades] = useState<Trade[]>([])
  const [balances, setBalances] = useState<Balance[]>([])
  const [orders, setOrders] = useState<Order[]>([])
  const [trades, setTrades] = useState<Trade[]>([])
  const [deposits, setDeposits] = useState<Deposit[]>([])
  const [withdrawals, setWithdrawals] = useState<Withdrawal[]>([])
  const [operations, setOperations] = useState<Operations | null>(null)
  const [stream, setStream] = useState<'connected' | 'reconnecting'>('reconnecting')
  const [notice, setNotice] = useState('')

  const refreshPublic = useCallback(async () => {
    const query = encodeURIComponent(MARKET)
    const [nextMarket, nextBook, nextTrades] = await Promise.all([
      request<Market>(`/api/public/markets/config?market=${query}`),
      request<Book>(`/api/public/markets/book?market=${query}&limit=20`),
      request<Trade[]>(`/api/public/markets/trades?market=${query}&limit=30`),
    ])
    setMarket(nextMarket); setBook(nextBook); setPublicTrades(nextTrades)
  }, [])

  const refreshPrivate = useCallback(async () => {
    const [nextBalances, nextOrders, nextTrades, nextDeposits, nextWithdrawals] = await Promise.all([
      request<Balance[]>('/api/account/balances', session),
      request<Order[]>('/api/account/orders', session),
      request<Trade[]>('/api/account/trades', session),
      request<Deposit[]>('/api/account/deposits', session),
      request<Withdrawal[]>('/api/account/withdrawals', session),
    ])
    setBalances(nextBalances); setOrders(nextOrders); setTrades(nextTrades)
    setDeposits(nextDeposits); setWithdrawals(nextWithdrawals)
  }, [session])

  const refreshOperations = useCallback(async () => {
    if (session.role === 'ADMIN') setOperations(await request<Operations>('/api/ops/overview', session))
  }, [session])

  const refreshAll = useCallback(async () => {
    try {
      await Promise.all([refreshPublic(), refreshPrivate(), refreshOperations()])
    } catch (reason) {
      setNotice(message(reason))
    }
  }, [refreshOperations, refreshPrivate, refreshPublic])

  useEffect(() => { void refreshAll() }, [refreshAll])
  useEffect(() => {
    const timer = window.setInterval(() => void refreshAll(), 5000)
    return () => window.clearInterval(timer)
  }, [refreshAll])
  useEffect(() => {
    const client = new Client({
      brokerURL: websocketUrl(), reconnectDelay: 2500, heartbeatIncoming: 10000,
      onConnect: () => {
        setStream('connected')
        client.subscribe('/topic/markets/BTC_USDT', frame => {
          const event = JSON.parse(frame.body) as { marketSequence?: number }
          if (event.marketSequence) void refreshAll()
        })
      },
      onWebSocketClose: () => setStream('reconnecting'),
      onStompError: () => setStream('reconnecting'),
    })
    client.activate()
    return () => { void client.deactivate() }
  }, [refreshAll])

  const lastPrice = publicTrades[0]?.price ?? null
  const tabs: { id: View; label: string; icon: LucideIcon }[] = [
    { id: 'trade', label: 'Trade', icon: BarChart3 },
    { id: 'portfolio', label: 'Portfolio', icon: WalletCards },
    { id: 'activity', label: 'Activity', icon: Activity },
  ]
  if (session.role === 'ADMIN') tabs.push({ id: 'operations', label: 'Operations', icon: ServerCog })

  return (
    <div className="app-shell">
      <header className="topbar">
        <div className="brand"><Zap size={18} /> AXIOM</div>
        <nav>{tabs.map(tab => <button key={tab.id} className={view === tab.id ? 'active' : ''} onClick={() => setView(tab.id)}><tab.icon size={16} />{tab.label}</button>)}</nav>
        <div className="user-menu"><span><b>{session.email}</b><small>{session.role}</small></span><button className="icon-button" title="Refresh" onClick={() => void refreshAll()}><RefreshCw size={17} /></button><button className="icon-button" title="Sign out" onClick={onLogout}><LogOut size={17} /></button></div>
      </header>

      <section className="ticker-band">
        <div className="pair"><span className="coin-mark">₿</span><div><strong>BTC / USDT</strong><small>Bitcoin spot</small></div></div>
        <Metric label="Last price" value={lastPrice === null || !market ? '—' : formatUnits(lastPrice, market.priceScale)} accent />
        <Metric label="Maker / Taker" value={market ? `${market.makerFeeBps} / ${market.takerFeeBps} bps` : '—'} />
        <Metric label="Open orders" value={String(orders.filter(order => ['NEW', 'PARTIALLY_FILLED'].includes(order.status)).length)} />
        <div className={`stream-state ${stream}`}><i />{stream === 'connected' ? 'Live' : 'Recovering'}</div>
      </section>

      {notice && <div className="notice"><span>{notice}</span><button title="Dismiss" onClick={() => setNotice('')}><X size={15} /></button></div>}
      <main className="workspace">
        {view === 'trade' && market && <TradeWorkspace market={market} book={book} trades={publicTrades} balances={balances} session={session} refresh={refreshAll} notify={setNotice} />}
        {view === 'portfolio' && <Portfolio balances={balances} withdrawals={withdrawals} session={session} refresh={refreshAll} notify={setNotice} />}
        {view === 'activity' && <ActivityView orders={orders} trades={trades} deposits={deposits} withdrawals={withdrawals} market={market} session={session} refresh={refreshAll} notify={setNotice} />}
        {view === 'operations' && operations && <OperationsView data={operations} />}
      </main>
    </div>
  )
}

function TradeWorkspace({ market, book, trades, balances, session, refresh, notify }: {
  market: Market; book: Book; trades: Trade[]; balances: Balance[]; session: Session
  refresh: () => Promise<void>; notify: (value: string) => void
}) {
  return <div className="trade-grid">
    <section className="panel order-book"><PanelTitle icon={BookOpen} title="Order book" meta="Price · Amount" /><BookTable levels={book.asks.slice().reverse()} side="ask" market={market} /><div className="spread-row"><span>Spread</span><strong>{spread(book, market.priceScale)}</strong></div><BookTable levels={book.bids} side="bid" market={market} /></section>
    <section className="panel recent-trades"><PanelTitle icon={Activity} title="Market trades" meta="Latest executions" /><table><thead><tr><th>Price</th><th>Amount</th><th>Time</th></tr></thead><tbody>{trades.map(trade => <tr key={trade.id}><td className="positive">{formatUnits(trade.price, market.priceScale)}</td><td>{formatUnits(trade.quantity, market.quantityScale)}</td><td>{time(trade.createdAt)}</td></tr>)}</tbody></table><Empty when={!trades.length} text="No executions" /></section>
    <OrderTicket market={market} balances={balances} session={session} refresh={refresh} notify={notify} />
  </div>
}

function OrderTicket({ market, balances, session, refresh, notify }: {
  market: Market; balances: Balance[]; session: Session; refresh: () => Promise<void>; notify: (value: string) => void
}) {
  const [side, setSide] = useState<'BUY' | 'SELL'>('BUY')
  const [type, setType] = useState<'LIMIT' | 'MARKET'>('LIMIT')
  const [price, setPrice] = useState('100.00')
  const [quantity, setQuantity] = useState('0.010000')
  const [busy, setBusy] = useState(false)
  const quote = balances.find(balance => balance.asset === market.quoteAsset)
  const base = balances.find(balance => balance.asset === market.baseAsset)
  const submit = async (event: FormEvent) => {
    event.preventDefault(); setBusy(true); notify('')
    try {
      const priceValue = parseUnits(price, market.priceScale)
      await request('/api/orders', session, { method: 'POST', body: JSON.stringify({
        market: market.symbol, side, type, timeInForce: type === 'LIMIT' ? 'GTC' : 'IOC',
        price: type === 'LIMIT' ? priceValue : null,
        protectionPrice: type === 'MARKET' ? priceValue : null,
        quantity: parseUnits(quantity, market.quantityScale),
      }) }, crypto.randomUUID())
      notify('Order accepted'); await refresh()
    } catch (reason) { notify(message(reason)) } finally { setBusy(false) }
  }
  return <section className="panel order-ticket"><PanelTitle icon={CircleDollarSign} title="Order ticket" meta={side === 'BUY' ? `Available ${formatBalance(quote)}` : `Available ${formatBalance(base)}`} /><div className="segmented"><button type="button" className={side === 'BUY' ? 'selected buy' : ''} onClick={() => setSide('BUY')}>Buy</button><button type="button" className={side === 'SELL' ? 'selected sell' : ''} onClick={() => setSide('SELL')}>Sell</button></div><div className="segmented compact"><button type="button" className={type === 'LIMIT' ? 'selected' : ''} onClick={() => setType('LIMIT')}>Limit</button><button type="button" className={type === 'MARKET' ? 'selected' : ''} onClick={() => setType('MARKET')}>Market</button></div><form onSubmit={submit} className="stack-form dense"><label>{type === 'LIMIT' ? 'Limit price' : 'Protection price'}<span className="input-unit"><input inputMode="decimal" value={price} onChange={event => setPrice(event.target.value)} required /><b>{market.quoteAsset}</b></span></label><label>Amount<span className="input-unit"><input inputMode="decimal" value={quantity} onChange={event => setQuantity(event.target.value)} required /><b>{market.baseAsset}</b></span></label><button className={`primary-button ${side.toLowerCase()}`} disabled={busy}>{busy ? 'Submitting...' : `${side === 'BUY' ? 'Buy' : 'Sell'} ${market.baseAsset}`}</button></form></section>
}

function Portfolio({ balances, withdrawals, session, refresh, notify }: {
  balances: Balance[]; withdrawals: Withdrawal[]; session: Session
  refresh: () => Promise<void>; notify: (value: string) => void
}) {
  const [asset, setAsset] = useState('BTC'); const [amount, setAmount] = useState('0.001000')
  const [address, setAddress] = useState('0x00000000000000000000000000000000000000b1')
  const selected = balances.find(balance => balance.asset === asset)
  const submit = async (event: FormEvent) => {
    event.preventDefault(); notify('')
    try { await request('/api/withdrawals', session, { method: 'POST', body: JSON.stringify({ asset, destinationAddress: address, amount: parseUnits(amount, selected?.scale ?? 6) }) }, crypto.randomUUID()); notify('Withdrawal moved to review'); await refresh() }
    catch (reason) { notify(message(reason)) }
  }
  const cancelWithdrawal = async (item: Withdrawal) => {
    try { await request(`/api/withdrawals/${item.id}/cancel`, session, { method: 'POST' }, crypto.randomUUID()); notify('Withdrawal canceled'); await refresh() }
    catch (reason) { notify(message(reason)) }
  }
  return <div className="portfolio-layout"><section className="panel wide"><PanelTitle icon={WalletCards} title="Asset balances" meta="Available and reserved" /><div className="balance-list">{balances.map(balance => <div className="balance-row" key={balance.asset}><div className={`asset-icon ${balance.asset.toLowerCase()}`}>{balance.asset[0]}</div><strong>{balance.asset}</strong><span><small>Available</small>{formatUnits(balance.available, balance.scale)}</span><span><small>Locked</small>{formatUnits(balance.locked, balance.scale)}</span><span><small>Total</small>{formatUnits(balance.available + balance.locked, balance.scale)}</span></div>)}</div></section><section className="panel withdrawal-panel"><PanelTitle icon={ArrowUpFromLine} title="Request withdrawal" meta="Manual review" /><form className="stack-form dense" onSubmit={submit}><label>Asset<select value={asset} onChange={event => setAsset(event.target.value)}>{balances.map(balance => <option key={balance.asset}>{balance.asset}</option>)}</select></label><label>Destination<input value={address} onChange={event => setAddress(event.target.value)} required /></label><label>Amount<input value={amount} onChange={event => setAmount(event.target.value)} inputMode="decimal" required /></label><button className="primary-button">Submit request</button></form></section><section className="panel wide"><PanelTitle icon={ArrowDownToLine} title="Recent withdrawals" meta={`${withdrawals.length} records`} /><WithdrawalTable items={withdrawals.slice(0, 8)} onCancel={cancelWithdrawal} /></section></div>
}

function ActivityView({ orders, trades, deposits, withdrawals, market, session, refresh, notify }: {
  orders: Order[]; trades: Trade[]; deposits: Deposit[]; withdrawals: Withdrawal[]
  market: Market | null; session: Session; refresh: () => Promise<void>; notify: (value: string) => void
}) {
  const cancelOrder = async (order: Order) => { try { await request(`/api/orders/${order.id}/cancel`, session, { method: 'POST', body: JSON.stringify({ market: order.market }) }, crypto.randomUUID()); await refresh() } catch (reason) { notify(message(reason)) } }
  const cancelWithdrawal = async (item: Withdrawal) => { try { await request(`/api/withdrawals/${item.id}/cancel`, session, { method: 'POST' }, crypto.randomUUID()); await refresh() } catch (reason) { notify(message(reason)) } }
  return <div className="activity-stack"><section className="panel"><PanelTitle icon={BookOpen} title="Orders" meta={`${orders.length} records`} /><div className="table-scroll"><table><thead><tr><th>ID</th><th>Market</th><th>Side</th><th>Type</th><th>Price</th><th>Filled</th><th>Status</th><th /></tr></thead><tbody>{orders.map(order => <tr key={order.id}><td>#{order.id}</td><td>{order.market}</td><td className={order.side === 'BUY' ? 'positive' : 'negative'}>{order.side}</td><td>{order.type} · {order.timeInForce}</td><td>{order.price === null || !market ? 'Market' : formatUnits(order.price, market.priceScale)}</td><td>{market ? formatUnits(order.originalQuantity - order.remainingQuantity, market.quantityScale) : '—'}</td><td><Status value={order.status} /></td><td>{['NEW', 'PARTIALLY_FILLED'].includes(order.status) && <button className="icon-button" title="Cancel order" onClick={() => void cancelOrder(order)}><X size={15} /></button>}</td></tr>)}</tbody></table></div><Empty when={!orders.length} text="No orders" /></section><section className="panel"><PanelTitle icon={Activity} title="My trades" meta={`${trades.length} executions`} /><TradeTable items={trades} market={market} /></section><section className="split-panels"><section className="panel"><PanelTitle icon={ArrowDownToLine} title="Deposits" meta="Chain observations" /><div className="table-scroll"><table><thead><tr><th>Asset</th><th>Amount</th><th>Block</th><th>Status</th></tr></thead><tbody>{deposits.map(deposit => <tr key={deposit.id}><td>{deposit.asset}</td><td>{deposit.internalAmount === null ? deposit.rawAmount : formatUnits(deposit.internalAmount, deposit.scale)}</td><td>#{deposit.blockNumber}</td><td><Status value={deposit.status} /></td></tr>)}</tbody></table></div><Empty when={!deposits.length} text="No deposits" /></section><section className="panel"><PanelTitle icon={ArrowUpFromLine} title="Withdrawals" meta="Custody queue" /><WithdrawalTable items={withdrawals} onCancel={cancelWithdrawal} /></section></section></div>
}

function OperationsView({ data }: { data: Operations }) {
  return <div className="operations-grid"><OpsMetric label="Outbox pending" value={data.pendingOutbox} tone="normal" /><OpsMetric label="Outbox dead" value={data.deadOutbox} tone={data.deadOutbox ? 'danger' : 'normal'} /><OpsMetric label="Deposit review" value={data.depositsInReview} tone={data.depositsInReview ? 'warning' : 'normal'} /><OpsMetric label="Withdrawal review" value={data.withdrawalsInReview} tone={data.withdrawalsInReview ? 'warning' : 'normal'} /><section className="panel operations-table"><PanelTitle icon={ServerCog} title="Chain indexers" meta="Persisted cursor" /><table><thead><tr><th>Network</th><th>Chain ID</th><th>Next block</th><th>Status</th><th>Reason</th></tr></thead><tbody>{data.chains.map(chain => <tr key={chain.chainId}><td>{chain.name}</td><td>{chain.chainId}</td><td>{chain.nextBlock}</td><td><Status value={chain.status} /></td><td>{chain.blockedReason ?? '—'}</td></tr>)}</tbody></table><Empty when={!data.chains.length} text="No configured chain indexer" /></section></div>
}

function BookTable({ levels, side, market }: { levels: Book['bids']; side: 'bid' | 'ask'; market: Market }) { return <div className={`book-side ${side}`}>{levels.map(level => <div className="book-row" key={level.price}><i style={{ width: `${Math.min(100, level.quantity / Math.max(1, ...levels.map(item => item.quantity)) * 100)}%` }} /><span>{formatUnits(level.price, market.priceScale)}</span><span>{formatUnits(level.quantity, market.quantityScale)}</span></div>)}<Empty when={!levels.length} text={`No ${side}s`} /></div> }
function TradeTable({ items, market }: { items: Trade[]; market: Market | null }) { return <><div className="table-scroll"><table><thead><tr><th>Sequence</th><th>Market</th><th>Price</th><th>Amount</th><th>Quote</th><th>Time</th></tr></thead><tbody>{items.map(trade => <tr key={trade.id}><td>#{trade.executionSequence}</td><td>{trade.market}</td><td>{market ? formatUnits(trade.price, market.priceScale) : trade.price}</td><td>{market ? formatUnits(trade.quantity, market.quantityScale) : trade.quantity}</td><td>{trade.quoteQuantity}</td><td>{time(trade.createdAt)}</td></tr>)}</tbody></table></div><Empty when={!items.length} text="No executions" /></> }
function WithdrawalTable({ items, onCancel }: { items: Withdrawal[]; onCancel?: (item: Withdrawal) => void }) { return <><div className="table-scroll"><table><thead><tr><th>ID</th><th>Asset</th><th>Amount</th><th>Destination</th><th>Status</th>{onCancel && <th />}</tr></thead><tbody>{items.map(item => <tr key={item.id}><td>#{item.id}</td><td>{item.asset}</td><td>{formatUnits(item.amount, item.scale)}</td><td className="mono">{shortHash(item.destinationAddress)}</td><td><Status value={item.status} /></td>{onCancel && <td>{item.status === 'REVIEW' && <button className="icon-button" title="Cancel withdrawal" onClick={() => onCancel(item)}><X size={15} /></button>}</td>}</tr>)}</tbody></table></div><Empty when={!items.length} text="No withdrawals" /></> }
function PanelTitle({ icon: Icon, title, meta }: { icon: LucideIcon; title: string; meta: string }) { return <header className="panel-title"><span><Icon size={16} />{title}</span><small>{meta}</small></header> }
function Metric({ label, value, accent = false }: { label: string; value: string; accent?: boolean }) { return <div className={`metric ${accent ? 'accent' : ''}`}><small>{label}</small><strong>{value}</strong></div> }
function OpsMetric({ label, value, tone }: { label: string; value: number; tone: string }) { return <div className={`ops-metric ${tone}`}><small>{label}</small><strong>{value}</strong></div> }
function Status({ value }: { value: string }) { return <span className={`status status-${value.toLowerCase()}`}>{value.replaceAll('_', ' ')}</span> }
function Empty({ when, text }: { when: boolean; text: string }) { return when ? <div className="empty">{text}</div> : null }

function spread(book: Book, scale: number) { if (!book.asks.length || !book.bids.length) return '—'; return formatUnits(book.asks[0].price - book.bids[0].price, scale) }
function formatBalance(balance?: Balance) { return balance ? `${formatUnits(balance.available, balance.scale)} ${balance.asset}` : '—' }
function formatUnits(value: number, scale: number) { const sign = value < 0 ? '-' : ''; const raw = Math.abs(value).toString().padStart(scale + 1, '0'); if (!scale) return sign + raw; const whole = raw.slice(0, -scale); const fraction = raw.slice(-scale).replace(/0+$/, ''); return `${sign}${whole}${fraction ? `.${fraction}` : ''}` }
function parseUnits(value: string, scale: number) { if (!/^\d+(\.\d+)?$/.test(value)) throw new Error('Enter a positive decimal amount'); const [whole, fraction = ''] = value.split('.'); if (fraction.length > scale) throw new Error(`Maximum ${scale} decimal places`); const result = Number(BigInt(whole) * 10n ** BigInt(scale) + BigInt(fraction.padEnd(scale, '0') || '0')); if (!Number.isSafeInteger(result) || result <= 0) throw new Error('Amount is outside the supported range'); return result }
function shortHash(value: string) { return `${value.slice(0, 8)}…${value.slice(-6)}` }
function time(value: string) { return new Date(value).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit' }) }
function message(reason: unknown) { return reason instanceof Error ? reason.message : 'Unexpected request failure' }
