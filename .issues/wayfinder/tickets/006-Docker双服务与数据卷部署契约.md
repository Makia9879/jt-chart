---
id: wf-formal-notify-006
title: 定义Docker双服务与数据卷部署契约
parent: wayfinder-formal-notification-redesign
labels:
  - wayfinder:task
status: closed
assignee: null
blocked_by:
  - wf-formal-notify-002
---

## Question

如何在现有 Compose 基础上定义 Web 与独立 Worker 的镜像复用、启动命令、健康检查、只读源码绑定挂载、`/data` SQLite 持久化卷、企微配置只读挂载以及备份/恢复边界，使容器重建不会丢失配置和待投递事件？

## Scope outcome

已移出本次最小可用目标：保留同镜像双服务、源码只读挂载和 `/data` 卷；不设计 SQLite、备份/恢复或完整健康检查契约。
