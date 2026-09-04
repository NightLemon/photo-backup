# Home Photo Backup API v1

服务发现使用 `_home-photo-backup._tcp.local.`。TXT 记录包含 `id=<server UUID>` 与 `api=1`。

## 配对载荷

管理页生成的二维码是 UTF-8 JSON：

```json
{
  "version": 1,
  "serverId": "uuid",
  "serverName": "Home Photo Backup",
  "port": 5443,
  "addresses": ["192.168.1.10"],
  "tlsSpkiSha256": "sha256/base64-value",
  "pairSecret": "single-use-secret",
  "expiresAt": 1735689600000
}
```

客户端固定 `tlsSpkiSha256` 后调用 `POST /api/v1/pair`。配对密钥为单次使用且五分钟过期。成功响应中的设备令牌用于后续 `Authorization: Bearer <token>`。

## 上传状态机

1. `POST /api/v1/uploads/prepare` 提交媒体元数据。
2. 若同一设备、媒体键、大小和修改时间已经完成，返回 `status=complete` 和原备份凭据。
3. 否则返回 `status=upload`、8 MiB 分块大小、会话 ID 和已收到的分块序号。
4. 缺失分块通过 `PUT /api/v1/uploads/{sessionId}/chunks/{index}` 上传，并在 `X-Chunk-SHA256` 中携带小写十六进制哈希。
5. `POST /api/v1/uploads/{sessionId}/finalize` 校验全部分块和整文件，原子落盘后返回备份凭据。

所有大小与偏移均为 64 位整数。客户端媒体键只在同一设备范围内有意义。服务端不信任客户端路径，所有目录名和文件名都会重新清理。

