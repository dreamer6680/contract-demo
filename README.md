# 煤炭买卖合同 DOCX → PDF Demo（第一版）

这是基于你提供的 `contract-template-v2.docx` 改造的原型，目标是先验证：**原 Word 模板占位符填充 → 生成 DOCX → 转换 PDF → 下载核对**。它不再依赖 MinIO，因此不会被 MinIO 镜像拉取问题阻塞。

## 启动环境

需要 Docker Desktop 和 Docker Compose。首次构建需要拉取 MySQL、Maven/Java、Node、LibreOffice 等基础镜像及依赖。

```bash
docker compose up --build -d
docker compose ps
docker compose logs -f backend
```

- 前端：http://localhost:5173
- 后端健康检查：http://localhost:8080/api/health
- MySQL：localhost:3307
- 生成文件：项目目录下 `storage/`

停止：

```bash
docker compose down
```

仅在确认要清空本地数据库时才执行 `docker compose down -v`。它会删除 MySQL 持久化卷。

## 第一版功能

- 以 `backend/src/main/resources/templates/coal-sale-contract.docx` 为原始 Word 模板。
- 填充卖方、买方、银行账户、合同编号、合同起止日期、煤炭品种/规格/数量/价格、交货信息和签署日期等字段。
- 生成填充后的 DOCX，并通过容器内 LibreOffice 转换成 PDF。
- 生成记录保存文件路径、生成时间和本次字段数据快照；前端可分别下载 PDF 和 DOCX。
- 新增“本次条款编辑”：运输、结算、违约责任、免责、争议解决等固定条款可在单次生成前修改或追加正文；修改不会写回原始 DOCX，也不会影响下一次生成。生成记录会保存本次条款快照。
- 中文字体依赖 `fonts-noto-cjk`，不使用 PDFBox 直接绘制合同正文。

## 重要说明

- 这是验证方案的 Demo，不是生产系统；没有登录、权限、合同审批、模板版本控制和审计。
- Word 中的占位符格式为 `{<$.seller.name>}`。第一版只对文档正文、表格、页眉页脚中的常规段落做占位符替换。
- 条款编辑采用请求级数据，不保存为公共模板配置。后端按原始合同段落锚点定位被修改的章节，未修改章节保持原样；新增行以段落内换行写入。生成前应检查最终 DOCX/PDF，确认新增条款没有造成不合适的分页。
- 对跨多个 Word run 的占位符，程序会合并段落文本再替换，可能影响该段落内部的局部字体/加粗样式；需要通过实际 PDF 核对。若有版式偏差，下一步会按原文件具体结构调整替换实现。
- LibreOffice 与 Microsoft Word 的排版引擎不同，页数、换行、分页符、表格高度或签署位置可能略有差异，不能保证像素级一致。
- 示例企业信息是测试数据，请勿将生成文件当成可直接签署的正式合同。
- 目前模板作为后端资源打包，修改原模板后需要重新构建 backend 镜像。

## 主要技术

React + TypeScript / Spring Boot 3.4 / Apache POI / LibreOffice headless / MySQL 8.4。
