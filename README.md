# MsVerify

面向 Paper 1.21.x / Java 21 的 Microsoft OAuth 验证插件。未验证玩家进服后会被冻结，直到外部验证服务返回通过结果。

插件本身不直接处理 Microsoft OAuth。它负责向验证中心申请短期 token/link、冻结未验证玩家并发送可点击验证链接、异步轮询 token 结果、把已验证玩家缓存到本地 SQLite。

## 构建

```powershell
mvn clean package
```

构建完成后，插件 jar 位于 `target/ms-verify-1.0.0.jar`。

## 服务器流程

1. 玩家进入 Paper 服务器。
2. 如果玩家已经存在于本地已验证缓存中，可以正常游玩。
3. 如果玩家尚未验证，插件异步请求 `GET /verify/gen`，请求头带 `X-Api-Key`。
4. 验证中心返回 `{ token, link, expiresAt }`，插件保存 `token -> Minecraft UUID` 映射，并把 `link` 发给玩家。
5. 玩家打开链接完成 Microsoft OAuth、Xbox/XSTS 验证；如果具备 Minecraft Services 权限，也可以继续执行 Minecraft profile 所有权校验。
6. 插件异步轮询 `GET /verify/get?token=<token>`，请求头同样带 `X-Api-Key`。
7. 远端返回邮箱和 UUID 后，插件检查 UUID 与本地 token 映射一致，再写入 SQLite；玩家会在下一次状态检查时解除冻结。

本地开发时，可以使用 [local-verifier/server.js](local-verifier/server.js)，并把插件的 gen/get 地址配置为 `http://127.0.0.1:8080`。

## 验证中心接口

生成链接：

```http
GET /verify/gen?serverId=survival-1&minecraftUuid=<uuid>&minecraftName=<name>
X-Api-Key: <api-key>
```

响应：

```json
{
  "token": "opaque-token",
  "link": "https://www.chaos-smp.cn/verify?token=opaque-token",
  "expiresAt": 1714000300000
}
```

查询结果：

```http
GET /verify/get?token=opaque-token
X-Api-Key: <api-key>
```

完成响应：

```json
{
  "status": "verified",
  "email": "player@example.com",
  "uuid": "minecraft-uuid",
  "minecraftUuid": "minecraft-uuid",
  "minecraftName": "Steve",
  "xuid": "123456789",
  "verifiedAt": 1714000100000
}
```

未完成时返回 `{ "status": "pending" }`。

## 命令

```text
/msverify reload
/msverify status <玩家名|UUID>
/msverify unverify <玩家名|UUID>
```

权限：`msverify.admin`。
