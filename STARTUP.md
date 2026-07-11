# 启动说明

本项目是 JT Regime 图表页和后台正式通知 Worker。默认包含两个 Docker 服务：

- `jt-regime-chart`: 静态页面和 HTTP API，默认监听 `8088`
- `jt-regime-worker`: 后台扫描已同步币对，触发正式通知

## 1. 准备本地配置

如果只使用原 WeCom 通道，确认外部 WeCom 文件存在：

```sh
/Volumes/samsung_disk_2T/openclaw_workspace/docker-cron/shared/wecom-notify/wecom.py
/Volumes/samsung_disk_2T/openclaw_workspace/docker-cron/shared/config/wecom.toml
```

如果同时启用 ElectricWave 手机通知通道，在项目根目录创建本地 `.env`：

```sh
ELECTRICWAVE_WEBHOOK_TOKEN=你的 webhook token
ELECTRICWAVE_RECEIVER_ID=phone-main
ELECTRICWAVE_ENDPOINT=https://notice.makia98.com/api/v1/notifications
```

`.env` 已被 `.gitignore` 忽略。不要把 token 写入代码、提交记录或日志。

## 2. 构建并启动

```sh
docker compose build
docker compose up -d
```

启动后访问：

```text
http://127.0.0.1:8088/
```

## 3. 同步后台监控配置

页面里选择数据源、周期和要通知的币对后，点击同步后台通知配置。后台 Worker 只会扫描已经同步到 `/data/monitor.json` 的配置。

可以用接口查看当前同步状态：

```sh
curl -sS http://127.0.0.1:8088/api/monitor/status
```

## 4. 验证通知通道

页面上的“测试当前币对通知”会调用：

```text
POST /api/wecom/notify
```

该接口现在会走复合通知通道：

- 永远保留原 WeCom 通道
- 配置了 `ELECTRICWAVE_WEBHOOK_TOKEN` 或 `WEBHOOK_TOKEN` 后，同时发送 ElectricWave

响应里的 `channels` 字段会显示每个通道的结果。

## 5. 停止服务

```sh
docker compose down
```

该命令会停止并移除容器和网络，但不会删除 `jt-regime-data` 数据卷。若要清空后台同步状态，需要另外处理数据卷或 `/data` 中的 JSON 文件。

## 6. 常用检查

```sh
docker compose ps
docker compose logs --tail=80 jt-regime-chart
docker compose logs --tail=80 jt-regime-worker
python3 -m unittest discover -s tests
python3 -m py_compile chart_server.py jt_shared.py jt_regime_worker.py tests/test_jt_regime.py
```
