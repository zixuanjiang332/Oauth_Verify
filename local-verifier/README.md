# MsVerify 本地验证服务

用于开发测试的 Microsoft OAuth 回调服务。

## Azure 设置

在 Azure 应用注册中添加这个重定向 URI：

```text
http://localhost:8080/oauth/microsoft/callback
```

应用需要支持个人 Microsoft 账号，这样 Minecraft/Xbox 账号才能登录。

## 运行

```powershell
node local-verifier/server.js
```

可选环境变量：

```powershell
$env:MSVERIFY_CLIENT_ID='74305fb6-cdd7-48fa-8fea-8d75e154cbd0'
$env:MSVERIFY_REDIRECT_URI='http://localhost:8080/oauth/microsoft/callback'
$env:MSVERIFY_SHARED_SECRET='local-test-change-this-secret-before-production-0123456789'
$env:MSVERIFY_SCOPE='XboxLive.signin openid email profile'
$env:MSVERIFY_ACCEPT_XBOX_ONLY='false'
```

如果使用 Web 平台重定向，而不是移动/桌面公有客户端重定向，还需要设置：

```powershell
$env:MSVERIFY_CLIENT_SECRET='你的 Azure 客户端密钥'
```

也可以从 `.env.example` 创建 `local-verifier/.env`。

## 临时仅 Xbox 验证模式

设置 `MSVERIFY_ACCEPT_XBOX_ONLY=true` 后，只要 Microsoft OAuth、Xbox Live 和 XSTS 验证成功就放行玩家。这个模式会跳过 Minecraft Services profile 校验，适合本地测试或拦截无法通过 Microsoft/Xbox 验证的账号，但不能证明该 Microsoft 账号拥有正在进服的 Minecraft UUID。

## 插件测试配置

```yaml
verification-start-url: "http://127.0.0.1:8080/start"
completed-endpoint: "http://127.0.0.1:8080/api/verifications/completed"
poller:
  enabled: true
```
