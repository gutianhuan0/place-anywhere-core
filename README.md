# Place Anywhere Core / 自由方块放置核心

Free block placement core system — place blocks at any position with any rotation.

自由方块放置核心系统 —— 支持在任意位置、任意旋转角度放置方块。



## Supported Versions / 支持版本

| Platform / 平台 | Versions / 版本 |
|---|---|
| Fabric | 1.18.2, 1.19.2, 1.19.4, 1.20.1, 1.20.6, 1.21.1 |
| Forge | 1.18.2, 1.19.2, 1.19.4, 1.20.1 |
| NeoForge | 1.20.6, 1.21.1 |

## Branches / 分支

Each branch corresponds to a specific platform + version combination.
每个分支对应一个特定的平台 + 版本组合。

- `fabric-1.18.2`
- `fabric-1.19.2`
- `fabric-1.19.4`
- `fabric-1.20.1`
- `fabric-1.20.6`
- `fabric-1.21.1`
- `forge-1.18.2`
- `forge-1.19.2`
- `forge-1.19.4`
- `forge-1.20.1`
- `forge-1.20.6`
- `forge-1.21.1`

> **Only `fabric-1.21.1` is actively developed. Other branches will be synced after 1.21.1 is stable.**
>
> **仅 `fabric-1.21.1` 为活跃开发分支，其他分支待 1.21.1 稳定后同步更新。**

## Features / 功能特性

- Place blocks at arbitrary positions / 任意位置放置方块
- Arbitrary rotation (quaternion) / 任意旋转（四元数）
- OBB collision detection (6-axis SAT) / OBB 碰撞检测（6轴 SAT）
- Redstone support / 红石系统支持
- Container GUI support / 容器 GUI 支持
- Server-authoritative sync / 服务端权威同步

## Build / 构建

Select the branch for your Minecraft version, then run:

选择对应 MC 版本的分支，然后运行：

```bash
# Windows
gradlew.bat build

# Linux / macOS
./gradlew build
```

## License / 许可证

MIT
