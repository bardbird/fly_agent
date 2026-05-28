# Fly Agent Chat Window - 设计文档

> **版本**: v1.0
> **日期**: 2026-03-02
> **作者**: Claude & 用户协作
> **状态**: 设计完成，待实施

---

## 📋 需求概述

### 目标
设计一个蓝色系、科技简约风格的 AI 对话窗口，支持流式输出、多会话管理和细腻的动效体验。

### 核心需求
- **使用场景**: AI 对话系统（类似 ChatGPT）
- **功能级别**: 增强对话（基础对话 + 流式输出 + Markdown 渲染 + 代码高亮 + 多会话管理 + 上下文编辑 + 重新生成）
- **布局方式**: 侧边栏布局（左侧会话列表 + 右侧聊天区域）
- **视觉风格**: 天蓝清新（浅色背景 + 渐变蓝，轻盈现代）

---

## 🏗️ 第一章：整体架构与布局设计

### 布局结构

```
┌─────────────────────────────────────────────────────┐
│                    Header (60px)                    │
├────────────┬────────────────────────────────────────┤
│            │                                        │
│  Sidebar   │         Chat Window                   │
│  (260px)   │         (Flex 1)                      │
│            │                                        │
│  ────────  │  ┌─────────────────────────────────┐  │
│  + 新建    │  │                                 │  │
│  ────────  │  │      Message List               │  │
│            │  │      (Scrollable)               │  │
│  会话 1    │  │                                 │  │
│  会话 2    │  │      [User]                     │  │
│  会话 3    │  │      [AI]                       │  │
│  ...       │  │                                 │  │
│            │  ├─────────────────────────────────┤  │
│            │  │                                 │  │
│            │  │      Input Area                 │  │
│            │  │      (Auto-resize)              │  │
│            │  │                                 │  │
│            │  └─────────────────────────────────┘  │
└────────────┴────────────────────────────────────────┘
```

### 技术栈选择

| 类别 | 技术选型 | 理由 |
|------|---------|------|
| **组件框架** | React 18 + TypeScript | 类型安全，生态成熟 |
| **状态管理** | Zustand | 轻量简洁，适合聊天场景 |
| **路由管理** | React Router v6 | 声明式路由 |
| **动效引擎** | Framer Motion | 细腻动画的主力工具 |
| **样式方案** | Tailwind CSS + CSS 变量 | 快速开发，易于定制 |
| **图标方案** | @iconify/react + Lucide-react | 20万+ 图标库 |
| **Markdown** | react-markdown + rehype-highlight | 富文本渲染 |
| **HTTP 客户端** | Axios | 成熟的请求库 |

### 核心组件划分

```
ChatApp/
├── Sidebar                      # 侧边栏（会话列表）
│   ├── ConversationList         # 会话列表
│   ├── ConversationItem         # 单个会话项
│   └── NewConversationButton    # 新建会话按钮
│
├── ChatWindow                   # 主聊天窗口
│   ├── Header                   # 顶部标题栏
│   ├── MessageList              # 消息列表
│   │   ├── MessageItem          # 单条消息
│   │   ├── StreamingIndicator   # 流式输出指示器
│   │   └── EmptyState           # 空状态插画
│   └── InputArea                # 输入区域
│       ├── TextInput            # 多行文本框
│       ├── Toolbar              # 工具栏
│       └── SendButton           # 发送按钮
│
└── ConversationManager          # 会话管理逻辑（HOC）
```

### 配色方案（天蓝清新）

```css
/* CSS 变量定义 */
:root {
  /* 背景色 */
  --bg-primary: #ffffff;
  --bg-secondary: #f0f9ff;
  --bg-tertiary: #e0f2fe;

  /* 主色调 - 天空蓝 */
  --primary-50: #f0f9ff;
  --primary-100: #e0f2fe;
  --primary-200: #bae6fd;
  --primary-300: #7dd3fc;
  --primary-400: #38bdf8;
  --primary-500: #0ea5e9;  /* 主色 */
  --primary-600: #0284c7;  /* 强调色 */
  --primary-700: #0369a1;
  --primary-800: #075985;
  --primary-900: #0c4a6e;  /* 深蓝文字 */

  /* 文字色 */
  --text-primary: #0c4a6e;
  --text-secondary: #0284c7;
  --text-tertiary: #7dd3fc;

  /* 渐变 */
  --gradient-primary: linear-gradient(135deg, #0ea5e9 0%, #0284c7 100%);
  --gradient-bg: linear-gradient(180deg, #e0f2fe 0%, #ffffff 100%);
}
```

---

## 🎨 第二章：核心组件设计详解

### 1. MessageItem 组件（消息气泡）

**功能特性**
- 差异化设计：用户消息 vs AI 消息
- Markdown 渲染 + 代码高亮
- 操作菜单（复制/重新生成/编辑）
- 消息状态（发送中/成功/失败）

**设计规范**

```typescript
// 用户消息（右侧，蓝色渐变）
<UserMessage>
  background: linear-gradient(135deg, #0ea5e9, #0284c7)
  color: white
  border-radius: 18px 18px 4px 18px
  max-width: 70%
  padding: 12px 16px
</UserMessage>

// AI 消息（左侧，白色卡片）
<AIMessage>
  background: white
  color: #0c4a6e
  border: 1px solid #bae6fd
  border-radius: 18px 18px 18px 4px
  max-width: 80%
  padding: 12px 16px
  box-shadow: 0 2px 8px rgba(14, 165, 233, 0.08)
</AIMessage>
```

**动效效果**
```typescript
<motion.div
  initial={{ opacity: 0, y: 10, scale: 0.95 }}
  animate={{ opacity: 1, y: 0, scale: 1 }}
  transition={{
    duration: 0.3,
    ease: [0.4, 0, 0.2, 1]
  }}
  whileHover={{ scale: 1.01 }}
>
  {/* 消息内容 */}
</motion.div>
```

**操作菜单**
- 悬停时淡入（延迟 200ms）
- 位置：消息气泡右上角
- 选项：复制 / 编辑 / 重新生成 / 删除

---

### 2. StreamingIndicator 组件（流式输出）

**三种状态**

| 状态 | 视觉表现 | 动效 |
|------|---------|------|
| **思考中** | 3 个蓝色圆点跳动 | 上下循环（0.6s） |
| **生成中** | 打字机效果 + 闪烁光标 | 逐字显示（每字 15ms） |
| **完成** | 光标淡出 | opacity: 1 → 0 (0.3s) |

**打字机效果实现**
```typescript
{currentResponse.split('').map((char, i) => (
  <motion.span
    key={i}
    variants={charVariants}
    initial="hidden"
    animate="visible"
    transition={{ duration: 0.05, delay: i * 0.015 }}
  >
    {char}
  </motion.span>
))}

// 闪烁光标
<motion.span
  animate={{ opacity: [1, 0, 1] }}
  transition={{ duration: 1.2, repeat: Infinity }}
  className="inline-block w-0.5 h-5 bg-primary-500 ml-1"
/>
```

---

### 3. InputArea 组件（输入区域）

**功能特性**
- 多行文本框（自适应高度，最大 200px）
- 工具栏（上传文件 / 语音输入 / @提及 Agent / 格式化）
- 发送按钮（有内容时蓝色飞机，禁用时灰色）
- 快捷键支持（Enter 发送，Shift+Enter 换行）

**UI 设计**
```tsx
<div className="border-t border-primary-200 bg-white p-4">
  {/* 工具栏 */}
  <div className="flex gap-2 mb-2">
    <IconButton icon="mdi:paperclip" label="上传文件" />
    <IconButton icon="mdi:microphone" label="语音输入" />
    <IconButton icon="mdi:at" label="@提及" />
    <IconButton icon="mdi:format-bold" label="格式化" />
  </div>

  {/* 输入框 + 发送按钮 */}
  <div className="flex gap-2">
    <textarea
      className="flex-1 resize-none rounded-lg border border-primary-300
                 bg-primary-50 px-4 py-3 text-primary-900
                 placeholder:text-primary-400
                 focus:border-primary-500 focus:ring-2 focus:ring-primary-200
                 transition-all"
      placeholder="输入消息... (Enter 发送, Shift+Enter 换行)"
      rows={1}
      style={{ maxHeight: '200px' }}
    />

    <motion.button
      whileHover={{ scale: 1.05 }}
      whileTap={{ scale: 0.95 }}
      className="rounded-lg bg-primary-500 p-3 text-white
                 hover:bg-primary-600 disabled:opacity-50"
      disabled={!content.trim()}
    >
      <Icon icon="mdi:send" className="w-5 h-5" />
    </motion.button>
  </div>
</div>
```

---

### 4. Sidebar 组件（侧边栏）

**布局结构**
```tsx
<aside className="w-260 bg-white border-r border-primary-200">
  {/* 新建会话按钮 */}
  <button className="m-4 p-3 rounded-xl bg-gradient-to-r
                     from-primary-500 to-primary-600 text-white
                     font-medium shadow-lg shadow-primary-500/30
                     hover:shadow-xl hover:shadow-primary-500/40
                     transition-all">
    + 新建对话
  </button>

  {/* 会话列表 */}
  <div className="overflow-y-auto px-2 pb-4">
    {conversations.map(conv => (
      <ConversationItem key={conv.id} {...conv} />
    ))}
  </div>
</aside>
```

**会话项动效**
```tsx
<motion.div
  whileHover={{ x: 4, backgroundColor: '#f0f9ff' }}
  whileTap={{ scale: 0.98 }}
  className="relative group p-3 mb-1 rounded-lg cursor-pointer
             transition-colors"
>
  <h3 className="font-medium text-primary-900 truncate">
    {conv.title}
  </h3>
  <p className="text-sm text-primary-600 truncate mt-1">
    {conv.lastMessage}
  </p>
  <span className="text-xs text-primary-400">
    {formatTime(conv.updatedAt)}
  </span>

  {/* 悬停操作按钮 */}
  <motion.div
    initial={{ opacity: 0 }}
    whileHover={{ opacity: 1 }}
    className="absolute right-2 top-1/2 -translate-y-1/2
               flex gap-1 opacity-0 group-hover:opacity-100
               transition-opacity"
  >
    <IconButton icon="mdi:pencil" size="sm" />
    <IconButton icon="mdi:delete" size="sm" />
  </motion.div>
</motion.div>
```

**折叠动画**
```tsx
<motion.aside
  animate={{ width: isSidebarOpen ? 260 : 0 }}
  transition={{
    type: 'spring',
    stiffness: 300,
    damping: 30
  }}
  className="overflow-hidden bg-white border-r border-primary-200"
>
  <AnimatePresence mode="wait">
    {isSidebarOpen && (
      <motion.div
        initial={{ opacity: 0 }}
        animate={{ opacity: 1 }}
        exit={{ opacity: 0 }}
        transition={{ duration: 0.2 }}
      >
        {/* 侧边栏内容 */}
      </motion.div>
    )}
  </AnimatePresence>
</motion.aside>
```

---

## 📊 第三章：数据流与状态管理

### Zustand Store 结构

```typescript
// store/chatStore.ts
interface ChatStore {
  // 会话状态
  conversations: Conversation[];
  currentConversationId: string | null;

  // 消息状态
  messages: Record<string, Message[]>; // { conversationId: [] }

  // UI 状态
  isSidebarOpen: boolean;
  isStreaming: boolean;
  streamingMessageId: string | null;

  // Actions - 会话管理
  createConversation: (title?: string) => string;
  deleteConversation: (id: string) => void;
  switchConversation: (id: string) => void;
  updateConversationTitle: (id: string, title: string) => void;

  // Actions - 消息操作
  sendMessage: (content: string) => Promise<void>;
  editMessage: (msgId: string, newContent: string) => Promise<void>;
  deleteMessage: (msgId: string) => void;
  regenerateResponse: (msgId: string) => Promise<void>;

  // Actions - UI 控制
  toggleSidebar: () => void;
  setStreaming: (isStreaming: boolean, msgId?: string) => void;
}

export const useChatStore = create<ChatStore>((set, get) => ({
  // 初始状态
  conversations: loadFromLocalStorage('conversations') || [],
  currentConversationId: null,
  messages: {},
  isSidebarOpen: true,
  isStreaming: false,
  streamingMessageId: null,

  // 实现...
}));
```

### 消息流向

```
用户输入 → sendMessage() → 乐观更新 → API 调用
                                      ↓
                               流式响应 (SSE)
                                      ↓
                         逐字更新 messages store
                                      ↓
                               UI 自动重新渲染
                                      ↓
                            保存到 IndexedDB
```

**详细流程**

```typescript
// 1. 用户发送消息
const sendMessage = async (content: string) => {
  const { currentConversationId } = get();

  // 创建用户消息
  const userMsg: Message = {
    id: generateId(),
    role: 'user',
    content,
    timestamp: Date.now(),
    status: 'sending'
  };

  // 乐观更新（立即上屏）
  set(state => ({
    messages: {
      ...state.messages,
      [currentConversationId]: [
        ...(state.messages[currentConversationId] || []),
        userMsg
      ]
    }
  }));

  try {
    // 2. 调用 API
    const response = await axios.post('/api/v1/chat/completions', {
      conversationId: currentConversationId,
      message: content
    });

    // 3. 处理流式响应
    const reader = response.body.getReader();
    const decoder = new TextDecoder();
    let aiContent = '';

    // 创建 AI 消息占位符
    const aiMsg: Message = {
      id: generateId(),
      role: 'assistant',
      content: '',
      timestamp: Date.now(),
      status: 'streaming'
    };

    set(state => ({
      messages: {
        ...state.messages,
        [currentConversationId]: [
          ...state.messages[currentConversationId],
          aiMsg
        ]
      },
      isStreaming: true,
      streamingMessageId: aiMsg.id
    }));

    // 4. 逐字读取
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;

      const chunk = decoder.decode(value);
      aiContent += chunk;

      // 更新消息内容
      set(state => ({
        messages: {
          ...state.messages,
          [currentConversationId]: state.messages[currentConversationId].map(msg =>
            msg.id === aiMsg.id ? { ...msg, content: aiContent } : msg
          )
        }
      }));
    }

    // 5. 完成
    set(state => ({
      messages: {
        ...state.messages,
        [currentConversationId]: state.messages[currentConversationId].map(msg =>
          msg.id === aiMsg.id ? { ...msg, status: 'success' } : msg
        )
      },
      isStreaming: false,
      streamingMessageId: null
    }));

    // 6. 保存到本地
    saveToLocalStorage('conversations', get().conversations);
    saveToIndexedDB('messages', get().messages);

  } catch (error) {
    // 错误处理
    set(state => ({
      messages: {
        ...state.messages,
        [currentConversationId]: state.messages[currentConversationId].map(msg =>
          msg.id === userMsg.id ? { ...msg, status: 'failed' } : msg
        )
      }
    }));
  }
};
```

### WebSocket 长连接（可选增强）

**用途**
- 实时推送 AI 响应
- 多端同步消息
- 在线状态指示

**实现**
```typescript
class ChatWebSocket {
  private ws: WebSocket | null = null;
  private reconnectAttempts = 0;
  private maxReconnectAttempts = 5;

  connect() {
    this.ws = new WebSocket('wss://api.example.com/chat');

    this.ws.onopen = () => {
      console.log('WebSocket 已连接');
      this.reconnectAttempts = 0;

      // 心跳检测
      setInterval(() => {
        this.ws?.send(JSON.stringify({ type: 'ping' }));
      }, 30000);
    };

    this.ws.onclose = () => {
      console.log('WebSocket 已断开');
      this.reconnect();
    };

    this.ws.onerror = (error) => {
      console.error('WebSocket 错误:', error);
    };

    this.ws.onmessage = (event) => {
      const data = JSON.parse(event.data);

      switch (data.type) {
        case 'message':
          // 处理新消息
          break;
        case 'typing':
          // 显示"对方正在输入..."
          break;
      }
    };
  }

  reconnect() {
    if (this.reconnectAttempts >= this.maxReconnectAttempts) {
      console.error('超过最大重连次数');
      return;
    }

    const delay = Math.pow(2, this.reconnectAttempts) * 1000; // 指数退避
    setTimeout(() => {
      this.reconnectAttempts++;
      this.connect();
    }, delay);
  }

  send(message: any) {
    this.ws?.send(JSON.stringify(message));
  }

  disconnect() {
    this.ws?.close();
  }
}
```

### 数据持久化策略

| 存储类型 | 用途 | 容量限制 | 读取速度 |
|---------|------|---------|---------|
| **Zustand Store (内存)** | 当前会话消息 | 不适用 | 最快 |
| **IndexedDB** | 完整对话历史 | 无限制 | 快 |
| **LocalStorage** | 用户设置、最近会话 | 5-10MB | 中等 |

**缓存策略**
```typescript
// 最近 50 条消息放内存
const RECENT_MESSAGES_LIMIT = 50;

// 分页加载历史消息
const loadMessages = async (conversationId: string, page: number) => {
  const cached = get().messages[conversationId];

  if (cached && cached.length < RECENT_MESSAGES_LIMIT) {
    // 从内存读取
    return cached;
  }

  // 从 IndexedDB 分页加载
  const messages = await loadFromIndexedDB(conversationId, page, 50);
  return messages;
};
```

---

## ✨ 第四章：细腻动效设计（Framer Motion）

### 1. 页面进入动画

```typescript
// 整个聊天窗口淡入 + 上移
const pageVariants = {
  initial: { opacity: 0, y: 20 },
  enter: { opacity: 1, y: 0 },
  exit: { opacity: 0, y: -20 }
};

<motion.div
  variants={pageVariants}
  initial="initial"
  animate="enter"
  exit="exit"
  transition={{
    duration: 0.5,
    ease: [0.4, 0, 0.2, 1] // 自定义贝塞尔曲线
  }}
>
  <ChatWindow />
</motion.div>
```

### 2. 消息列表交错动画

```typescript
// 方式一：delay 交错
{messages.map((msg, i) => (
  <motion.div
    key={msg.id}
    initial={{ opacity: 0, y: 10, scale: 0.95 }}
    animate={{ opacity: 1, y: 0, scale: 1 }}
    transition={{
      delay: i * 0.05, // 每条延迟 50ms
      duration: 0.3,
      ease: 'easeOut'
    }}
  >
    <MessageItem message={msg} />
  </motion.div>
))}

// 方式二：staggerChildren（性能更好）
const listVariants = {
  hidden: { opacity: 0 },
  show: {
    opacity: 1,
    transition: {
      staggerChildren: 0.05 // 子元素交错延迟
    }
  }
};

const itemVariants = {
  hidden: { opacity: 0, y: 10 },
  show: { opacity: 1, y: 0 }
};

<motion.ul
  variants={listVariants}
  initial="hidden"
  animate="show"
>
  {messages.map(msg => (
    <motion.li key={msg.id} variants={itemVariants}>
      <MessageItem message={msg} />
    </motion.li>
  ))}
</motion.ul>
```

### 3. 流式输出逐字动画

```typescript
// AI 消息打字机效果
const charVariants = {
  hidden: {
    opacity: 0,
    y: 5,
    filter: 'blur(10px)'
  },
  visible: {
    opacity: 1,
    y: 0,
    filter: 'blur(0px)'
  }
};

<motion.div>
  {content.split('').map((char, i) => (
    <motion.span
      key={i}
      variants={charVariants}
      initial="hidden"
      animate="visible"
      transition={{
        duration: 0.05,
        delay: i * 0.015 // 每字延迟 15ms
      }}
    >
      {char}
    </motion.span>
  ))}

  {/* 闪烁光标 */}
  <motion.span
    animate={{ opacity: [1, 0, 1] }}
    transition={{ duration: 1.2, repeat: Infinity }}
    className="inline-block w-0.5 h-5 bg-primary-500 ml-0.5"
  />
</motion.div>
```

### 4. 侧边栏折叠动画

```typescript
// Spring 动画（弹性效果）
<motion.aside
  animate={{ width: isSidebarOpen ? 260 : 0 }}
  transition={{
    type: 'spring',
    stiffness: 300, // 刚度
    damping: 30,    // 阻尼
    mass: 0.8       // 质量
  }}
  className="overflow-hidden bg-white border-r border-primary-200"
>
  <AnimatePresence mode="wait">
    {isSidebarOpen && (
      <motion.div
        initial={{ opacity: 0 }}
        animate={{ opacity: 1 }}
        exit={{ opacity: 0 }}
        transition={{ duration: 0.2 }}
      >
        <SidebarContent />
      </motion.div>
    )}
  </AnimatePresence>
</motion.aside>
```

### 5. 按钮微交互

```typescript
// 悬停 + 点击反馈
<motion.button
  whileHover={{
    scale: 1.02,
    boxShadow: '0 4px 12px rgba(14, 165, 233, 0.2)'
  }}
  whileTap={{ scale: 0.98 }}
  whileFocus={{
    boxShadow: '0 0 0 3px rgba(14, 165, 233, 0.3)'
  }}
  disabled={disabled}
  className="px-4 py-2 rounded-lg bg-primary-500 text-white
             disabled:opacity-50 disabled:cursor-not-allowed
             transition-colors"
>
  {children}
</motion.button>
```

### 6. 加载动画

```typescript
// 三个跳动的点
<motion.div className="flex gap-1">
  {[0, 1, 2].map(i => (
    <motion.div
      key={i}
      animate={{
        y: [0, -10, 0],
        opacity: [0.5, 1, 0.5]
      }}
      transition={{
        duration: 0.6,
        repeat: Infinity,
        delay: i * 0.1
      }}
      className="w-2 h-2 rounded-full bg-primary-500"
    />
  ))}
</motion.div>

// Lottie 动画（蓝色云朵）
import { Player } from '@lottiefiles/react-lottie-player';

<Player
  autoplay
  loop
  src="/animations/blue-cloud.json"
  style={{ height: '100px', width: '100px' }}
/>
```

### 7. 滚动效果

```typescript
// 自动滚动到底部
useEffect(() => {
  if (messagesEndRef.current) {
    messagesEndRef.current.scrollIntoView({
      behavior: 'smooth',
      block: 'end'
    });
  }
}, [messages]);

// 弹性滚动（iOS 风格）
<div
  className="overflow-y-auto"
  style={{
    overscrollBehavior: 'contain',
    WebkitOverflowScrolling: 'touch'
  }}
>
  {/* 消息列表 */}
</div>
```

### 8. 布局动画（Layout Animation）

```typescript
// 消息位置变化时自动平滑过渡
<motion.div layout>
  {messages.map(msg => (
    <MessageItem key={msg.id} message={msg} />
  ))}
</motion.div>
```

### 动效性能优化

```typescript
// ✅ 使用 GPU 加速属性
animate={{ x: 100, opacity: 0.5, scale: 1.1 }}

// ❌ 避免使用触发重排的属性
animate={{ width: 100, height: 100, top: 50 }}

// ✅ 使用 will-change 提示浏览器
<motion.div
  style={{ willChange: 'transform, opacity' }}
  animate={{ scale: 1.1 }}
/>

// ✅ 使用 layoutId 实现共享元素过渡
<motion.div layoutId="card">
  {isExpanded ? <ExpandedView /> : <CollapsedView />}
</motion.div>
```

---

## 🛡️ 第五章：错误处理与边界情况

### 1. 网络错误处理

```typescript
// API 错误分类处理
const handleApiError = (error: AxiosError) => {
  switch (error.response?.status) {
    case 401:
      // 未授权 → 跳转登录
      showError('请先登录');
      navigate('/login');
      break;

    case 429:
      // 请求过多 → 显示重试按钮
      showError('请求过于频繁，请稍后再试', {
        action: '重试',
        callback: () => retry()
      });
      break;

    case 500:
      // 服务器错误 → 标记消息失败
      updateMessageStatus(msgId, 'failed');
      showError('服务器错误，请稍后再试');
      break;

    default:
      // 网络错误
      if (!navigator.onLine) {
        showError('网络连接已断开，请检查网络');
      } else {
        showError('发送失败，请重试');
      }
  }
};
```

### 2. 流式中断处理

```typescript
// 用户主动打断
const abortController = new AbortController();

const sendMessage = async (content: string) => {
  try {
    await axios.post('/api/chat', {
      signal: abortController.signal
    });
  } catch (error) {
    if (error.name === 'AbortError') {
      console.log('用户取消了请求');
      // 标记消息为已取消
      updateMessageStatus(msgId, 'cancelled');
    }
  }
};

// 用户发送新消息时，取消当前流式请求
useEffect(() => {
  if (isStreaming && hasNewMessage) {
    abortController.abort();
  }
}, [hasNewMessage]);
```

### 3. 输入验证

```typescript
// 空消息验证
const canSend = content.trim().length > 0;

// 超长消息限制
const MAX_LENGTH = 4000;
const charCount = content.length;
const isTooLong = charCount > MAX_LENGTH;

// 敏感内容过滤（基础）
const containsSensitiveContent = (text: string) => {
  const sensitiveWords = ['密码', 'token', 'api_key'];
  return sensitiveWords.some(word =>
    text.toLowerCase().includes(word)
  );
};
```

### 4. 边界情况处理

```typescript
// 无会话 → 自动创建欢迎会话
useEffect(() => {
  if (conversations.length === 0) {
    const welcomeMsg: Message = {
      id: generateId(),
      role: 'assistant',
      content: '你好！我是 Fly Agent，有什么可以帮你的吗？',
      timestamp: Date.now()
    };

    createConversation('新对话', [welcomeMsg]);
  }
}, []);

// 空状态插画
{messages.length === 0 && (
  <motion.div
    initial={{ opacity: 0, scale: 0.9 }}
    animate={{ opacity: 1, scale: 1 }}
    className="flex flex-col items-center justify-center h-full text-primary-400"
  >
    <img src="/images/empty-state.svg" alt="空状态" />
    <p className="mt-4 text-lg">开始新对话</p>
  </motion.div>
)}

// 移动端适配
{isMobile && (
  <motion.div
    initial={{ x: '-100%' }}
    animate={{ x: isSidebarOpen ? 0 : '-100%' }}
    className="fixed inset-0 z-50 w-80 bg-white"
  >
    <Sidebar />
  </motion.div>
)}
```

### 5. 加载状态

```typescript
// Skeleton 屏幕（消息加载占位）
<motion.div
  animate={{ opacity: [0.5, 1, 0.5] }}
  transition={{ duration: 1.5, repeat: Infinity }}
  className="h-16 bg-primary-100 rounded-lg"
/>

// 乐观更新（立即上屏）
const sendMessage = async (content: string) => {
  // 立即添加到 UI
  const tempMsg: Message = {
    id: generateId(),
    role: 'user',
    content,
    timestamp: Date.now(),
    status: 'sending'
  };

  setMessages(prev => [...prev, tempMsg]);

  try {
    await api.send(content);
    // 更新状态为成功
    updateMessageStatus(tempMsg.id, 'success');
  } catch (error) {
    // 回滚或标记失败
    updateMessageStatus(tempMsg.id, 'failed');
  }
};
```

### 6. 离线支持

```typescript
// 监听网络状态
useEffect(() => {
  const handleOnline = () => {
    showToast('网络已恢复');
    // 重试失败的消息
    retryFailedMessages();
  };

  const handleOffline = () => {
    showToast('网络已断开', 'error');
  };

  window.addEventListener('online', handleOnline);
  window.addEventListener('offline', handleOffline);

  return () => {
    window.removeEventListener('online', handleOnline);
    window.removeEventListener('offline', handleOffline);
  };
}, []);
```

---

## 🧪 测试策略

### 1. 单元测试（Vitest）

```typescript
// store/chatStore.test.ts
import { describe, it, expect } from 'vitest';
import { useChatStore } from './chatStore';

describe('ChatStore', () => {
  it('应该创建新会话', () => {
    const { createConversation, conversations } = useChatStore.getState();

    const id = createConversation('测试会话');

    expect(conversations).toHaveLength(1);
    expect(conversations[0].id).toBe(id);
    expect(conversations[0].title).toBe('测试会话');
  });

  it('应该发送消息', async () => {
    const { sendMessage, messages } = useChatStore.getState();

    await sendMessage('你好');

    expect(messages[currentConversationId]).toHaveLength(1);
    expect(messages[currentConversationId][0].content).toBe('你好');
  });
});
```

### 2. 组件测试（React Testing Library）

```typescript
// components/MessageItem.test.tsx
import { render, screen } from '@testing-library/react';
import { MessageItem } from './MessageItem';

describe('MessageItem', () => {
  it('应该渲染用户消息', () => {
    const userMsg = {
      id: '1',
      role: 'user',
      content: '你好',
      timestamp: Date.now()
    };

    render(<MessageItem message={userMsg} />);

    expect(screen.getByText('你好')).toBeInTheDocument();
    expect(screen.getByText('你好')).toHaveClass('text-white');
  });

  it('悬停时应该显示操作菜单', async () => {
    render(<MessageItem message={userMsg} />);

    fireEvent.mouseEnter(screen.getByText('你好'));

    await waitFor(() => {
      expect(screen.getByLabelText('复制')).toBeInTheDocument();
      expect(screen.getByLabelText('删除')).toBeInTheDocument();
    });
  });
});
```

### 3. 集成测试（Playwright）

```typescript
// tests/e2e/chat.spec.ts
import { test, expect } from '@playwright/test';

test.describe('聊天功能', () => {
  test('应该完成端到端对话流程', async ({ page }) => {
    await page.goto('http://localhost:6677');

    // 创建新会话
    await page.click('button:has-text("新建对话")');

    // 发送消息
    await page.fill('textarea', '你好');
    await page.click('button[aria-label="发送"]');

    // 等待 AI 响应
    await expect(page.locator('.message-ai')).toBeVisible();
    await expect(page.locator('.message-ai')).toContainText(/你好/);

    // 验证消息已保存
    await page.reload();
    await expect(page.locator('.message-user')).toContainText('你好');
  });

  test('应该支持流式输出', async ({ page }) => {
    await page.goto('http://localhost:6677');

    await page.fill('textarea', '写一首诗');
    await page.click('button[aria-label="发送"]');

    // 验证打字机效果
    const streamingIndicator = page.locator('.streaming-indicator');
    await expect(streamingIndicator).toBeVisible();

    // 等待完成
    await expect(streamingIndicator).not.toBeVisible({ timeout: 10000 });
  });
});
```

### 4. 性能测试

```typescript
// 长列表渲染性能
import { test } from 'vitest';
import { render } from '@testing-library/react';
import { MessageList } from './MessageList';

test('应该流畅渲染 1000 条消息', () => {
  const messages = Array.from({ length: 1000 }, (_, i) => ({
    id: `msg-${i}`,
    role: i % 2 === 0 ? 'user' : 'assistant',
    content: `消息 ${i}`,
    timestamp: Date.now() - i * 1000
  }));

  const startTime = performance.now();
  render(<MessageList messages={messages} />);
  const endTime = performance.now();

  expect(endTime - startTime).toBeLessThan(1000); // 1秒内完成
});

// 动画帧率测试
test('应该保持 60fps 动画', async () => {
  let frames = 0;
  const startTime = performance.now();

  const measureFrame = () => {
    frames++;
    if (performance.now() - startTime < 1000) {
      requestAnimationFrame(measureFrame);
    }
  };

  requestAnimationFrame(measureFrame);

  await waitFor(() => expect(frames).toBeGreaterThan(55));
});
```

---

## 📦 附录：完整依赖清单

```json
{
  "dependencies": {
    "react": "^18.3.0",
    "react-dom": "^18.3.0",
    "react-router-dom": "^6.22.0",

    "framer-motion": "^11.0.0",
    "@iconify/react": "^4.1.1",
    "lucide-react": "^0.344.0",

    "zustand": "^4.5.0",
    "@tanstack/react-query": "^5.28.0",

    "axios": "^1.6.7",
    "react-markdown": "^9.0.0",
    "rehype-highlight": "^7.0.0",

    "clsx": "^2.1.0",
    "tailwind-merge": "^2.2.0",

    "@lottiefiles/react-lottie-player": "^1.5.0"
  },
  "devDependencies": {
    "@types/react": "^18.3.0",
    "@types/react-dom": "^18.3.0",
    "@vitejs/plugin-react": "^4.2.1",
    "typescript": "^5.4.0",
    "vite": "^5.1.0",

    "tailwindcss": "^3.4.0",
    "autoprefixer": "^10.4.18",
    "postcss": "^8.4.35",

    "vitest": "^1.3.0",
    "@testing-library/react": "^14.2.0",
    "@playwright/test": "^1.42.0",

    "eslint": "^8.57.0",
    "prettier": "^3.2.5"
  }
}
```

---

## 🚀 下一步行动

1. ✅ **设计已完成**
2. ⏳ **创建实施计划**（使用 `superpowers:writing-plans`）
3. ⏳ **设置 Git 工作树**（使用 `superpowers:using-git-worktrees`）
4. ⏳ **开始实施**

---

> **文档状态**: 设计完成，等待用户确认后进入实施阶段
> **最后更新**: 2026-03-02
