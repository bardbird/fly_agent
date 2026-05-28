# Fly Agent Web - 快速启动指南

## 🚀 快速开始

### 1. 安装依赖(如果还未安装)

```bash
npm install
```

### 2. 启动开发服务器

```bash
npm run dev
```

服务器将在 http://localhost:6677 启动

### 3. 在浏览器中打开

访问 http://localhost:6677 查看应用

## 📦 可用脚本

- `npm run dev` - 启动开发服务器
- `npm run build` - 构建生产版本
- `npm run preview` - 预览生产构建
- `npm run lint` - 运行 ESLint 检查

## 🔧 开发前准备

### 后端服务

确保后端服务运行在 http://localhost:8080

```bash
cd fly-agent-server
mvn spring-boot:run
```

### 环境变量

创建 `.env` 文件(已包含):

```
VITE_API_BASE_URL=http://localhost:8080
```

## 🎨 功能特性

- ✅ 现代化 UI 设计(天蓝色渐变)
- ✅ 流畅动画效果
- ✅ 实时聊天功能
- ✅ 会话管理
- ✅ 状态持久化
- ✅ 响应式布局

## 📝 开发说明

### 项目结构

```
src/
├── components/       # React 组件
│   ├── ui/          # 基础 UI 组件
│   ├── chat/        # 聊天相关组件
│   └── layout/      # 布局组件
├── store/           # Zustand 状态管理
├── lib/             # 工具函数和 API
├── types/           # TypeScript 类型
└── App.tsx          # 应用入口
```

### 添加新功能

1. 在 `src/components/` 中创建新组件
2. 在 `src/types/` 中添加类型定义
3. 在 `src/lib/api.ts` 中添加 API 调用
4. 在 `src/store/chatStore.ts` 中更新状态

## 🐛 常见问题

### 端口被占用

如果 6677 端口被占用,可以修改 `vite.config.ts`:

```typescript
server: {
  port: 6678, // 改为其他端口
}
```

### API 请求失败

确保:
1. 后端服务正在运行
2. 端口 8080 未被占用
3. CORS 配置正确

### 样式不生效

清除缓存并重启:

```bash
rm -rf node_modules/.vite
npm run dev
```

## 📚 技术栈

- **React 19** - UI 框架
- **TypeScript** - 类型安全
- **Vite** - 构建工具
- **Tailwind CSS v4** - 样式框架
- **Framer Motion** - 动画库
- **Zustand** - 状态管理
- **Axios** - HTTP 客户端

## 🎯 下一步

1. 连接真实的后端 API
2. 实现用户认证
3. 添加更多功能(文件上传、代码高亮等)
4. 优化移动端体验
5. 添加单元测试

## 💡 提示

- 使用 TypeScript 严格模式
- 遵循现有的代码风格
- 添加适当的注释
- 测试你的更改

## 📞 支持

如有问题,请查看 README.md 或提交 Issue
