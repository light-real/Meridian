# Meridian

> 多市场交易 App · 加密货币 / A 股 / 美股 / 港股
> 一个 App 内按 Tab 切换市场，真实行情 + 虚拟资金，不涉及真实资金。

## 市场

四个市场不做成独立 App，全部装在同一个 App 里，通过 Tab 切换；账户、下单、盈亏、持仓、记录共用一套引擎。

| 市场 | 状态 | 行情来源 |
|------|------|----------|
| 加密货币 · USDT 永续合约 | 已实现 | Binance USDⓈ-M 公开接口 |
| A 股 · 沪深京 | 基础版已实现 | 新浪涨幅榜 / 腾讯报价 / 东方财富搜索，经 `gbrilliant.xyz` 中转 |
| 美股 | 规划中 | 待定 |
| 港股 | 规划中 | 待定 |

当前版本已实现加密货币和 A 股基础版；A 股先做现货、T+1、市价单，涨停/跌停和手续费按 A 股规则计算。美股、港股后续作为新的 Tab 加入同一个 App。

## 交易复盘（网站）

App 端只负责下单和平仓，不写理由。理由、删除和复盘都在网站 `/trading/` 上做：

- 每笔成交右侧有「理由」，点开填写 / 修改，保存到 `trade_notes` 表
- 每笔成交和整组仓位都能删除（`deleted_trades` 表），App 下次同步也不会把它加回来
- 「交易复盘」按仓位分组：同一笔仓位分成多单开、多单平，会自动合并统计开仓量 / 均价、平仓量 / 均价、剩余、净盈亏、收益率、手续费
- 合约表、A 股表、CSV 导出都会带上网站写的理由
- 旧记录没有 `positionId`，网站按「同币种、开仓后到下一次开仓前」自动补配对

删除只影响网站归档和统计，不改 App 里的账户余额和持仓。

## 当前实现：加密货币

加密货币部分是一个本地优先的合约交易盘：行情、K 线、盘口来自 Binance USDⓈ-M 永续合约公开接口，下单和盈亏全部在本地计算。适合用来练习杠杆、仓位和强平，不需要注册交易所账户，也不会产生真实资金往来。

- 当前版本：**1.0.0**（versionCode 1，开发中）
- 包名：`com.meridian.app`
- 系统要求：Android 8.0（API 26）及以上
- 安装包：暂未发布，需要的话按下方「构建」自行编译

首次启动可选择游客模式（仅本机）或登录同步；游客数据只存本机，登录后可通过 Supabase 同步到云端。

## 功能

- **行情**：USDT 永续合约列表、24h 涨跌幅、搜索、自选
- **盘口**：实时买卖盘（depth5 推送，1 秒刷新）
- **交易**：做多 / 做空、1–150x、逐仓 / 全仓、按名义价值下单
- **风控**：开仓/平仓手续费、维持保证金、强平价预估、实时强平
- **持仓**：未实现盈亏、占用保证金、部分平仓 / 全部平仓、逐仓调整保证金
- **记录**：开仓 / 平仓 / 强平 / 资金费流水，按类型筛选
- **账户**：游客模式（仅本地）/ 邮箱登录（Supabase 云同步）、手动同步、重置合约账户

## A 股（基础版）

A 股是独立的现货账户，初始资金 100,000 CNY，与 USDT 合约账户完全隔离。

- **行情**：涨幅榜（前 100）、自选、代码 / 名称搜索、最新价、涨跌幅、今开 / 最高 / 最低 / 昨收 / 涨跌停
- **交易**：市价买入 / 卖出，数量按 100 股整数倍
- **规则**：T+1（当天买入次日可卖）、主板 10% / 创业板与科创板 20% / 北交所 30% / ST 5% 涨跌停、新股 N/C 不设涨跌停
- **费用**：佣金 0.025%（最低 5 元）、卖出印花税 0.05%、过户费 0.001%
- **账户**：独立资金、持仓、交易记录，可在「我的」页单独重置
- **自选**：本地保存，和合约自选分开
- **暂未实现**：限价单、公司行动（拆股 / 分红）、自选分组排序、Level-2 盘口

行情不直接在 App 内请求上游，而是走 `gbrilliant.xyz/a-share/` 中转，避免手机开启代理/VPN 时被上游断开。中转代码和地址记录在 `OPS.md`。

## 技术栈

| 模块 | 选型 |
|------|------|
| 语言 | Kotlin 2.0.21 |
| UI | Jetpack Compose（BOM 2024.12.01）+ Material 3 |
| 导航 | Navigation Compose |
| 状态 | ViewModel + StateFlow |
| 本地存储 | Room 2.6.1（KSP） |
| 网络 | OkHttp 4.12（REST + WebSocket） |
| 序列化 | kotlinx.serialization |
| 异步 | Kotlin Coroutines |
| 云端 | Supabase Auth + REST（直接 HTTP，不引 SDK） |
| 构建 | AGP 8.13.0 / Gradle 8.14.3 / JDK 17 |

## 项目结构

Android 工程位于 `android-native/`：

```
android-native/
├── app/src/main/java/com/meridian/app/
│   ├── core/
│   │   ├── data/          # Room、Binance REST/WS、Supabase、会话加密
│   │   ├── domain/        # TradingEngine、OrderCalculator、格式化
│   │   ├── model/         # Account / Position / Trade / Ticker ...
│   │   └── designsystem/  # 颜色、字体、通用组件
│   ├── feature/
│   │   ├── market/        # 行情、自选、搜索
│   │   ├── ashare/        # A 股行情、交易、持仓估值
│   │   ├── trade/         # 盘口、下单、平仓
│   │   ├── positions/     # 持仓与盈亏
│   │   ├── history/       # 交易记录
│   │   ├── profile/       # 账户、同步、重置
│   │   └── auth/          # 登录 / 注册 / 游客
│   ├── navigation/        # 底部 Tab 与路由
│   ├── AppContainer.kt    # 手动依赖注入
│   └── MainActivity.kt
├── app/src/test/          # 交易引擎单元测试
└── gradle/libs.versions.toml
```

数据流：

```
行情源（Binance 等）──► Repository ──► ViewModel(StateFlow) ──► Compose UI
                              │
                              ├──► Room（本地账户 / 持仓 / 记录）
                              └──► Supabase（可选云同步）
```

加一个新市场时，只需要在 `core/data/` 下接一个行情源、在 `feature/` 下加对应的列表和交易页，交易引擎、账户和记录直接复用，不改动已有市场。

## 交易规则（加密货币）

| 项目 | 规则 |
|------|------|
| 初始资金 | 10,000 USDT |
| 杠杆 | 1–150x，逐仓 |
| 开仓手续费 | 名义价值 × 0.1% |
| 平仓手续费 | 名义价值 × 0.1% |
| 维持保证金率 | 0.5% |
| 强平价（多） | 开仓价 × (1 − (1 − 0.5%) / 杠杆) |
| 强平价（空） | 开仓价 × (1 + (1 − 0.5%) / 杠杆) |
| 强平条件 | 未实现盈亏 ≤ −保证金 × (1 − 0.5%) |
| 加仓 | 同方向、同杠杆合并，开仓价按数量加权 |
| 反向开仓 | 拒绝，需先平掉原持仓 |
| 部分平仓 | 按比例释放保证金；盈亏扣手续费后返还，最低为 0 |

开仓时扣除 `保证金 + 开仓手续费`；平仓时返还 `释放的保证金 + 盈亏 − 平仓手续费`。强平会损失该仓位全部保证金。

## 行情与成交（加密货币）

- 行情和 K 线来自 `https://fapi.binance.com`，WebSocket 来自 `wss://fstream.binance.com`，不需要 API Key。
- 交易对只保留 `PERPETUAL` + `USDT` + `TRADING` 的合约，并按 Binance 的 `tickSize` / `stepSize` / `minNotional` 校验。
- 市价单按最新成交价即时成交，**没有滑点、资金费率、限价单、止盈止损**。
- 盘口优先使用实时 depth；断线时会用最新价生成占位盘口占位，不是真实挂单。

## 云同步（可选）

不登录也能完整使用，数据只在本机。登录后使用 Supabase 同步账户快照和自选。

### 1. 建表

在 Supabase SQL Editor 中执行：

```sql
-- 账户快照
create table if not exists public.user_data (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null unique references auth.users(id) on delete cascade,
  cash numeric default 10000,
  stocks jsonb default '[]'::jsonb,
  history jsonb default '[]'::jsonb,
  total_assets numeric default 10000,
  profit numeric default 0,
  updated_at timestamptz default now()
);

-- 自选
create table if not exists public.favorite_symbols (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null unique references auth.users(id) on delete cascade,
  symbols jsonb default '[]'::jsonb,
  updated_at timestamptz default now()
);

-- 行级权限：每个用户只能读写自己的数据
alter table public.user_data enable row level security;
alter table public.favorite_symbols enable row level security;

create policy "user_data_own" on public.user_data
  for all using (auth.uid() = user_id) with check (auth.uid() = user_id);

create policy "favorite_symbols_own" on public.favorite_symbols
  for all using (auth.uid() = user_id) with check (auth.uid() = user_id);
```

### 2. 配置客户端

在 `android-native/local.properties` 中填入：

```properties
SUPABASE_URL=https://<your-project>.supabase.co
SUPABASE_ANON_KEY=<your-anon-key>
```

两项留空时，App 自动退化为游客模式。`anon key` 本身设计为公开，但**必须开启 RLS**，否则别人可以读写所有用户数据。

### 3. 同步行为

- 登录成功后：云端有数据则覆盖本地；云端没有则把本地当前账户上传。
- 每笔开仓 / 平仓 / 强平后异步上传，也可以在「我的」页手动同步。
- 上传的是整份账户快照（现金 + 持仓 + 最近 1,000 条记录），采用 last-write-wins，多设备并发不会合并。

登录会话使用 Android Keystore 的 AES/GCM 加密后落盘，不保存明文 token。

## 构建

### 环境要求

- JDK 17 及以上（Gradle 8.14.3 可用 JDK 21 运行）
- Android SDK Platform 36
- Android Studio（推荐）或命令行 SDK

### 配置 SDK

`android-native/local.properties`（该文件已被 `.gitignore` 忽略）：

```properties
sdk.dir=C:/Users/<you>/AppData/Local/Android/Sdk
```

> 从 macOS 迁移过来的项目，`sdk.dir` 仍是 Mac 路径，需要改成当前机器的 SDK 路径。

### 编译与测试

```bash
cd android-native

# Windows
gradlew.bat assembleDebug
gradlew.bat assemblePerf
gradlew.bat test
gradlew.bat assembleRelease

# macOS / Linux
./gradlew assembleDebug
./gradlew assemblePerf
./gradlew test
./gradlew assembleRelease
```

产物：

- Debug：`app/build/outputs/apk/debug/app-debug.apk`（可调试，但 Compose 帧耗时明显更高）
- Perf：`app/build/outputs/apk/perf/app-perf.apk`（与 Debug 同包名、同签名，可直接覆盖安装并保留数据；非 debuggable，日常使用和测流畅度装这个）
- Release：`app/build/outputs/apk/release/app-release.apk`

### 签名

Release 签名读取 `android-native/keystore.properties`：

```properties
storeFile=../meridian-release.keystore
storePassword=******
keyAlias=meridian
keyPassword=******
```

`storeFile` 相对于 `android-native/`，因此指向项目根目录的 keystore。缺失该文件时，release 构建不会使用正式签名。

> **不要把 `meridian-release.keystore`、`keystore.properties`、`local.properties` 提交到仓库或随安装包外发。** 拿到 keystore 和密码的人可以签出同包名的“更新版”。这些文件已在 `.gitignore` 中排除。

## 测试

```bash
gradlew.bat testDebugUnitTest
```

当前有 10 个交易 / 下单单元测试，覆盖：

- 开仓扣保证金和手续费
- 同方向加仓的加权开仓价
- 反向开仓、杠杆不一致的拒绝
- 超额平仓的数量 clamp
- 部分平仓的保证金释放
- 强平边界（90.05 触发 / 90.06 不触发）
- 总资产与未实现盈亏
- 下单预览的余额与最小名义价值校验
- 100% 保证金下单仍预留手续费

测试报告：`app/build/reports/tests/testDebugUnitTest/index.html`

## 已知限制

1. **行情依赖 Binance 公共接口**。中国大陆网络通常无法直连 `fapi.binance.com` / `fstream.binance.com`，需要自备网络条件；App 目前没有内置代理或备用行情源。
2. **强平只在「行情 / 持仓 / 交易」页收到对应币种行情时检测**。杀进程、断网、停留在「我的」页期间不会检查，也不会按历史价格补算；再次打开后按收到的最新价判断。
3. **云同步是整包覆盖、last-write-wins**。新设备登录会用云端数据覆盖本地；多设备同时交易可能互相覆盖。
4. **没有资金费率、滑点、限价单、止盈止损**。所有订单按最新价即时成交，和真实交易所的撮合仍有差距。
5. **盘口断线时会显示本地占位盘口**，不是真实挂单，仅用于界面占位。
6. 没有崩溃上报和埋点；Release 未开启代码混淆和资源压缩。

## 免责声明

本项目仅用于学习和技术演示，所有交易均为本地虚拟记账，不涉及真实资金，不构成任何投资建议。加密资产价格波动剧烈，高杠杆会放大亏损，请勿将虚拟结果直接用于真实交易决策。

## 许可证

本项目目前为个人项目，暂未声明开源许可证。如需公开，请补充 `LICENSE` 文件。
