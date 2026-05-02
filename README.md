# MsVerify

面向 Paper 1.21.x / Java 21 的 Microsoft OAuth 验证插件。未验证玩家进服后会被冻结，直到外部验证服务返回通过结果。

插件本身不直接处理 Microsoft OAuth。它负责生成短期 HMAC 签名 challenge、冻结未验证玩家并发送可点击验证链接、异步轮询外部服务、把已验证玩家缓存到本地 SQLite。

## 构建

```powershell
mvn clean package
```

构建完成后，插件 jar 位于 `target/ms-verify-1.0.0.jar`。

## 服务器流程

1. 玩家进入 Paper 服务器。
2. 如果玩家已经存在于本地已验证缓存中，可以正常游玩。
3. 如果玩家尚未验证，会在服务器内被冻结，并收到一条可点击的 Microsoft OAuth 验证链接。
4. Microsoft 登录完成后，会带着 `code` 和插件生成的 `state` 回调到外部验证服务。
5. 外部验证服务校验 challenge，执行 Xbox/XSTS 验证；如果具备 Minecraft Services 权限，也可以继续执行 Minecraft profile 所有权校验。
6. 插件异步轮询 `/api/verifications/completed`。
7. 完成记录写入 SQLite 后，玩家会在下一次状态检查时解除冻结，也可以重新进服。

本地开发时，可以使用 [local-verifier/server.js](local-verifier/server.js)，并把插件的 start/completed 地址配置为 `http://127.0.0.1:8080`。

## Challenge 令牌

格式：

```text
base64url(payloadJson).base64url(hmacSha256(payloadJson, sharedSecret))
```

payload 字段：

```json
{
  "v": 1,
  "challengeId": "uuid",
  "serverId": "survival-1",
  "minecraftUuid": "玩家 UUID",
  "minecraftName": "玩家名",
  "issuedAt": 1714000000000,
  "expiresAt": 1714000300000,
  "nonce": "base64url 随机字节"
}
```

外部服务需要用恒定时间比较校验 HMAC，拒绝过期令牌，并把每个 `challengeId` 当作一次性 challenge 处理。

## 轮询协议

请求：

```http
GET /api/verifications/completed?serverId=survival-1&since=<cursor>
X-MSVerify-Server: survival-1
X-MSVerify-Timestamp: 1714000000000
X-MSVerify-Signature: <base64url hmac>
```

签名原文：

```text
GET
<原始 path 和 query>
<serverId>
<since cursor>
<timestamp 毫秒>
```

响应：

```json
{
  "items": [
    {
      "challengeId": "uuid",
      "minecraftUuid": "uuid",
      "minecraftName": "Steve",
      "xuid": "123456789",
      "verifiedAt": 1714000100000
    }
  ],
  "nextCursor": "123456"
}
```

`cursor` 由外部服务维护，推荐使用数据库自增 ID。插件只会在完成写入 SQLite 后推进本地 cursor。

## 命令

```text
/msverify reload
/msverify status <玩家名|UUID>
/msverify unverify <玩家名|UUID>
```

权限：`msverify.admin`。
