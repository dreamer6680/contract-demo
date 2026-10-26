# ONLYOFFICE 占位符锁定 POC

## 目标

验证合同模板中的 `{<$.product.type>}` 格式业务占位符能否在 ONLYOFFICE Docs 中作为内联内容控件锁定，同时允许用户编辑同一段落中的普通文字、修改其他排版，并保存 DOCX。

## 当前状态

本分支先加入可选的 ONLYOFFICE Docs 容器，尚未完成编辑器前端接入、内容控件注入或保存回调。不能把“启动了 Document Server”视为锁定行为已验证。

## 启动 Document Server

在项目根目录执行：

```bash
export ONLYOFFICE_JWT_SECRET='replace-with-a-long-random-secret'
docker compose --profile onlyoffice up -d onlyoffice
docker compose ps onlyoffice
curl -fsS http://localhost:8081/healthcheck
```

Document Server 地址：`http://localhost:8081`。JWT 密钥只用于本地开发，正式部署必须由后端签发/验证 JWT，并通过 HTTPS 和受控网络访问 Document Server；不要把密钥放进 React 前端。

## 验证用例

准备一个独立测试 DOCX，包含以下段落：

`产品类型：{<$.product.type>}，规格及交付要求由双方协商确定。`

预期检查：

1. `{<$.product.type>}` 不可修改。
2. 占位符不可删除。
3. 同一段落中的“产品类型：”和“规格及交付要求由双方协商确定。”仍可编辑。
4. 可修改其他段落、字体、对齐、表格布局等。
5. 保存并重新打开 DOCX 后，占位符仍然存在且锁定。
6. 后端在保存时检查占位符集合，发现占位符缺失、增删或改名时拒绝保存，并返回可理解的错误信息。

## 技术验证顺序

1. 通过 ONLYOFFICE Docs API 打开测试 DOCX。
2. 用 ONLYOFFICE Office API 将每个占位符转换成内联内容控件，并设置 `SetLock("sdtContentLocked")`。
3. 在编辑器中测试锁定占位符与同段普通文字的可编辑性。若 ONLYOFFICE 对 inline content control 的锁定行为导致整段文字无法按预期编辑，需改用更细粒度的文档结构或插件方案。
4. 接入后端保存回调；保存后解析 DOCX，验证占位符名称和数量与原模板一致。
5. 通过实际编辑、保存、重新打开的回归测试后，才可认定 POC 通过。

## 参考文档

- [ONLYOFFICE React 集成](https://api.onlyoffice.com/docs/docs-api/get-started/frontend-frameworks/react)
- [ApiInlineLvlSdt.SetLock](https://api.onlyoffice.com/docs/office-api/usage-api/document-api/ApiInlineLvlSdt/Methods/SetLock/)
- [SdtLock 枚举](https://api.onlyoffice.com/docs/office-api/usage-api/document-api/Enumeration/SdtLock/)
