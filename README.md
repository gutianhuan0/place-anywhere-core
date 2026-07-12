# Place Anywhere Core / 自由方块放置核心

Free block placement core system — place blocks at any position with any rotation.

自由方块放置核心系统 —— 支持在任意位置、任意旋转角度放置方块。

---

## Branch: `forge-1.21.1` / 分支：`forge-1.21.1`

| | |
|---|---|
| **Loader / 加载器** | NeoForge / NeoForge |
| **Minecraft Version / MC 版本** | 1.21.1 |
| **Java Version / Java 版本** | 21 |
| **Status / 状态** | **Maintenance only — will be synced after Fabric 1.21.1 is stable.** / **仅维护 —— 待 Fabric 1.21.1 稳定后同步更新。** |



## Build / 构建

```bash
# Windows
gradlew.bat build

# Linux / macOS
./gradlew build
```

The built JAR will be in `build/libs/`.

构建好的 JAR 文件在 `build/libs/` 目录下。

## Features / 功能特性

- Place blocks at arbitrary positions / 任意位置放置方块
- Arbitrary rotation (quaternion) / 任意旋转（四元数）
- OBB collision detection (6-axis SAT) / OBB 碰撞检测（6轴 SAT）
- Redstone support / 红石系统支持
- Container GUI support / 容器 GUI 支持
- Server-authoritative sync / 服务端权威同步

## Supported Versions / 支持版本

| Platform / 平台 | Versions / 版本 |
|---|---|
| Fabric | 1.18.2, 1.19.2, 1.19.4, 1.20.1, 1.20.6, 1.21.1 |
| Forge | 1.18.2, 1.19.2, 1.19.4, 1.20.1 |
| NeoForge | 1.20.6, 1.21.1 |

> **Only Fabric 1.21.1 is actively developed. Other versions will be synced after 1.21.1 is stable.**
>
> **仅 Fabric 1.21.1 为活跃开发版本，其他版本待 1.21.1 稳定后同步更新。**

## License / 许可证

MIT
