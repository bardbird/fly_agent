# Fly Agent Web - 前端聊天界面

一个基于 React 18 + TypeScript + Vite + Tailwind CSS 构建的现代化 AI 聊天界面,采用天蓝色清新设计风格。

## 技术栈

- **框架**: React 18 + TypeScript
- **构建工具**: Vite
- **样式**: Tailwind CSS + 自定义主题
- **动画**: Framer Motion
- **状态管理**: Zustand
- **图标**: @iconify/react
- **Markdown**: react-markdown

## 特性

- ✨ 现代化 UI 设计 - 天蓝色渐变配色,玻璃拟态效果
- 🎨 流畅动画 - Framer Motion 驱动的细腻动效
- 💬 实时聊天 - 支持流式 AI 响应
- 📱 响应式布局 - 适配桌面和移动设备
- 💾 状态持久化 - 本地存储对话历史
- 🎯 TypeScript - 完整类型安全

## 快速开始

### 安装依赖

```bash
npm install
```

### 启动开发服务器

```bash
npm run dev
```

访问 http://localhost:6677

### 构建生产版本

```bash
npm run build
```

### 预览生产版本

```bash
npm run preview
```

## 项目结构

```
src/
├── components/
│   ├── ui/              # 基础 UI 组件
│   │   ├── button.tsx
│   │   ├── icon-button.tsx
│   │   └── loading.tsx
│   ├── chat/            # 聊天相关组件
│   │   ├── MessageItem.tsx
│   │   ├── MessageList.tsx
│   │   └── InputArea.tsx
│   └── layout/          # 布局组件
│       ├── Sidebar.tsx
│       └── ChatLayout.tsx
├── store/               # Zustand 状态管理
│   └── chatStore.ts
├── lib/                 # 工具函数和 API
│   ├── utils.ts
│   └── api.ts
├── types/               # TypeScript 类型定义
│   └── chat.ts
├── App.tsx              # 应用入口
└── main.tsx             # React 挂载
```

## 设计理念

### 色彩系统

- **主色调**: 天蓝色 (#0ea5e9)
- **辅助色**: 渐变蓝 (#0284c7)
- **背景色**: 渐变白蓝 (#f0f9ff)

### 动效设计

- **页面加载**: 渐入效果
- **消息气泡**: 缩放 + 位移动画
- **侧边栏**: 弹簧动画
- **按钮**: 悬停缩放

### 玻璃拟态

使用 backdrop-blur 和半透明背景创建现代玻璃效果:

```css
.glass {
  @apply bg-white/80 backdrop-blur-lg border border-white/20;
}
```

## API 集成

### 后端地址

开发环境默认代理到: http://localhost:8080

### 主要接口

- `POST /api/v1/chat/completions` - 发送消息(流式)
- `GET /api/v1/conversations` - 获取会话列表
- `POST /api/v1/conversations` - 创建会话
- `DELETE /api/v1/conversations/:id` - 删除会话

## 功能特性

### 1. 消息发送

- 支持 Enter 发送,Shift+Enter 换行
- 自动调整输入框高度
- 发送状态指示

### 2. 会话管理

- 创建新会话
- 切换会话
- 删除会话(带确认)
- 自动保存到本地

### 3. 流式响应

- 打字机效果显示 AI 响应
- 实时滚动到底部
- 加载动画

### 4. 侧边栏

- 平滑展开/收起动画
- 会话列表显示
- 时间戳格式化

## 开发说明

### 添加新的 API 接口

在 `src/lib/api.ts` 中添加:

```typescript
export async function myNewApi(params: any) {
  const response = await api.get('/endpoint', { params })
  return response.data
}
```

### 修改主题颜色

编辑 `tailwind.config.js`:

```javascript
colors: {
  primary: {
    500: '#your-color',
    // ...
  }
}
```

### 添加新组件

1. 在对应目录创建组件文件
2. 导出组件函数
3. 在需要的地方导入使用

## 浏览器支持

- Chrome (最新版)
- Firefox (最新版)
- Safari (最新版)
- Edge (最新版)

## License

Apache License 2.0
