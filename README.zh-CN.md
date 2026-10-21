# Exchange Java

这是一个使用 Java 21、Spring Boot、MySQL、Kafka、Redis、Web3j 和 React 实现的数字资产现货交易参考工程。项目重点不是复刻大型交易所的全部基础设施，而是把下单、锁资、撮合、结算、账本、可靠事件、行情投影和 EVM 充值索引放进一条可运行、可测试的链路中。

[English README](README.md)

![交易控制台](docs/images/trading-console.png)

## 系统组成

```text
React 页面
  -> JWT 鉴权与 REST 请求
  -> Spring Boot 应用服务
  -> 事务内撮合、锁资、成交结算与账本
  -> MySQL 权威数据
  -> Outbox -> Kafka -> Inbox/持久化投影
  -> Redis 行情视图与 WebSocket 实时通知

EVM JSON-RPC
  -> ERC-20 Transfer 扫描
  -> 确认数与重组检查
  -> MySQL 充值状态与内部余额入账
```

MySQL 保存订单、钱包、成交、账本、充值、提现和幂等记录等权威事实。Kafka 负责传递已经提交的事件；Redis 和 WebSocket 只保存或传递可重建的行情数据，不能决定用户真实余额。

## 已实现能力

- LIMIT/MARKET、GTC/IOC/FOK、价格时间优先、部分成交和自成交防护。
- `available/locked` 余额、定点数金额、手续费、价格改善释放和追加式账本。
- 数据库幂等键、请求摘要、钱包固定加锁顺序、并发与事务回滚测试。
- Transactional Outbox、Kafka ACK、重试、Inbox 去重和持久化行情投影。
- Redis 可重建行情视图、市场序列号和 STOMP/WebSocket 推送。
- ERC-20 `Transfer` 解码、扫描游标、确认门槛、重复事件保护和浅重组处理。
- JWT、角色权限、交易与资产 API、React 页面、健康探针、指标和 trace。

## 本地运行

需要 JDK 21、Node.js 24 和 Docker Desktop。

```bash
docker compose up -d mysql kafka redis

EXCHANGE_REDIS_PORT=6380 \
EXCHANGE_MESSAGING_ENABLED=true \
EXCHANGE_DEMO_ENABLED=true \
SPRING_PROFILES_ACTIVE=observability \
./mvnw spring-boot:run
```

另开终端：

```bash
cd frontend
npm ci
npm run dev
```

访问 <http://localhost:5173>。本地演示账号：

- 交易用户：`trader@example.com` / `Trader123!`
- 运营用户：`admin@example.com` / `Admin123!`

完整容器方式：

```bash
cp .env.example .env
docker compose --profile app up --build
```

访问 <http://localhost:4173>。

## 验证

```bash
./scripts/verify.sh
node scripts/smoke-test.mjs
```

第一条命令运行 Java 单元测试、架构测试、Testcontainers 集成测试和前端生产构建。第二条命令需要完整应用已经启动，用于验证登录、幂等下单、撤单和资金释放。

## 真实边界

当前项目不是生产托管交易所，不包含私钥托管、链上提现签名与广播、MPC/HSM、AML/KYT、多副本撮合共识、多地域容灾或生产 SLA。EVM 充值链路使用 HTTP JSON-RPC 适配测试和数据库状态机测试验证，没有使用真实资金或生产节点完成认证。

详细设计见 [docs](docs/architecture.md)，许可证为 [MIT](LICENSE)。
