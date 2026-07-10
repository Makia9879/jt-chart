---
id: wayfinder-formal-notification-redesign
title: 后台常驻正式通知重构路线图
labels:
  - wayfinder:map
status: closed
---

## Destination

形成一份最小可用设计：Docker 中由独立后台 Worker 复刻当前页面的 JT Regime 通知行为；用户在页面点击一次，即可将当前设置同步到挂载的数据文件，Worker 对已收盘 K 线发送“试探抄底”和“确认抄底”。

## Notes

- 这是规划地图，不在本地图中实施功能。
- 保持 `jt-regime-oscillator.html` 的公式及标记语义；不得以简化阈值模型替代。
- 页面只新增“一键同步到后台”与简要运行状态；后台监控不得依赖浏览器或 `localStorage`。
- Docker 采用 Web 与 Worker 两个服务，源码只读挂载，配置与已发送信号使用 `/data` 挂载目录中的小型 JSON 文件。
- 最小版本不引入数据库、通用事件账本、历史通知页或认证系统。
- 每次处理子票据前，按其类型使用 `/grilling` 或相应研究流程；一次会话只关闭一个票据。

## Decisions so far

- [定义公式一致性与闭合K线判定边界](tickets/001-公式一致性与闭合K线判定.md) — 以 `jt-regime-v1` 原样复现当前公式；仅在收盘后 10 秒纳入计算，并以固定夹具逐字段验证页面与 Worker 一致。
- [定义最小后台配置文件格式](tickets/002-监控配置与事件账本数据模型.md) — 页面一次性同步 `/data/monitor.json`，Worker 以持久化信号键去重并写 `/data/worker-status.json`；JSON 方案接受发送与落键之间的极小重复窗口。
- [定义后台扫描与收盘调度机制](tickets/003-后台扫描与收盘调度.md) — Worker 每 15 秒扫描已收盘满 10 秒的 K 线，按同步时间建立基线，并在成功投递后写入信号键；失败由下一轮重试。

## Not yet specified

- 无；最小版本的扫描、重试及状态语义已确定。

## Out of scope

- 其他通知渠道、策略公式本身的交易逻辑改造，以及自动下单。
- SQLite、通用事件账本、投递状态机、历史通知审计页、配置 API 认证与自动迁移旧 `localStorage`。
