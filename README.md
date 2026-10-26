# 合同 PDF 生成原型

这是一个基于 React + TypeScript、Spring Boot、Apache POI、LibreOffice 和 MySQL 的合同模板填充与 PDF 生成演示项目。

## 启动

```bash
docker compose up --build -d
```

- 前端： http://localhost:5173
- 后端健康检查： http://localhost:8080/api/health
- MySQL： localhost:3307

## 目录

- `frontend/`：React + Vite 前端
- `backend/`：Spring Boot API、DOCX 模板填充及 PDF 转换
- `backend/src/main/resources/templates/coal-sale-contract.docx`：合同 Word 模板

> 当前为功能原型，尚未在目标环境完成 Docker 全链路验证。正式使用前应补充权限校验、文件安全策略、异常处理与部署配置。
