# Fly Agent Platform - React 前端技术方案

> **文档版本**: v1.0
> **更新时间**: 2025-02-05
> **用途**: React 前端技术选型和最佳实践

---

## 📦 核心技术栈

### 基础框架
```
React 18+          # UI 框架
TypeScript 5+      # 类型安全
Vite 5+            # 构建工具
```

### 路由和状态管理
```
React Router v6    # 路由管理
Zustand / Jotai    # 轻量级状态管理
React Query        # 服务端状态管理
```

### UI 组件和样式
```
shadcn/ui          # 组件库（推荐）
Tailwind CSS 3.4+  # 原子化 CSS
clsx / tailwind-merge  # 类名工具
```

### 动效方案
```
Framer Motion      # 主力动画库
GSAP               # 复杂时间轴动画（可选）
Lottie-web         # 设计师主导动画（可选）
```

### 图标方案
```
@iconify/react     # 主力图标方案（200,000+ 图标）
Lucide-react       # 高质量精选图标（补充）
```

---

## 🎨 动效方案详解

### 1. Framer Motion（主力）

**安装**
```bash
npm install framer-motion
```

**使用示例**
```jsx
import { motion } from 'framer-motion';

// 基础动画
<motion.div
  initial={{ opacity: 0 }}
  animate={{ opacity: 1 }}
  transition={{ duration: 0.5 }}
/>

// 悬停效果
<motion.button
  whileHover={{ scale: 1.05 }}
  whileTap={{ scale: 0.95 }}
>

// 列表交错动画
{items.map((item, i) => (
  <motion.div
    key={item.id}
    initial={{ opacity: 0, y: 20 }}
    animate={{ opacity: 1, y: 0 }}
    transition={{ delay: i * 0.1 }}
  />
))}

// 布局动画（自动处理元素位置变化）
<motion.div layout>
  {items.map(item => <Item key={item.id} />)}
</motion.div>

// 拖拽
<motion.div
  drag
  dragConstraints={{ left: 0, right: 300 }}
/>

// 路由过渡（配合 React Router）
import { AnimatePresence } from 'framer-motion';

<AnimatePresence mode="wait">
  <motion.div
    key={location.pathname}
    initial={{ opacity: 0, x: -20 }}
    animate={{ opacity: 1, x: 0 }}
    exit={{ opacity: 0, x: 20 }}
    transition={{ duration: 0.3 }}
  >
    <Routes />
  </motion.div>
</AnimatePresence>
```

**适用场景**
- ✅ 页面过渡动画
- ✅ 列表增删动画
- ✅ 悬停/点击交互
- ✅ 拖拽交互
- ✅ 布局变化动画

---

### 2. GSAP（复杂动画，可选）

**安装**
```bash
npm install gsap
```

**使用示例**
```jsx
import { gsap } from 'gsap';
import { ScrollTrigger } from 'gsap/ScrollTrigger';
import { useLayoutEffect, useRef } from 'react';

gsap.registerPlugin(ScrollTrigger);

function ScrollAnimation() {
  const container = useRef();

  useLayoutEffect(() => {
    const ctx = gsap.context(() => {
      gsap.from('.box', {
        scrollTrigger: {
          trigger: '.box',
          start: 'top 80%',
          end: 'top 20%',
          scrub: true,
        },
        x: 100,
        rotation: 360,
      });
    }, container);

    return () => ctx.revert();
  }, []);

  return <div ref={container}>
    <div className="box">滚动触发动画</div>
  </div>;
}

// 时间轴编排
useLayoutEffect(() => {
  const tl = gsap.timeline({ repeat: -1, repeatDelay: 1 });

  tl.to('.box1', { x: 100, duration: 1 })
    .to('.box2', { y: 100, duration: 1 }, '-=0.5')
    .to('.box3', { rotation: 360, duration: 1 }, '-=0.5');
}, []);
```

**适用场景**
- ✅ 滚动触发动画（ScrollTrigger）
- ✅ 复杂时间轴编排
- ✅ 精确控制动画序列
- ✅ 高性能要求场景

---

### 3. Lottie（设计师主导动画，可选）

**安装**
```bash
npm install lottie-web
# 或 React 封装
npm install @lottiefiles/react-lottie-player
```

**使用示例**
```jsx
import Lottie from 'lottie-web';
import { useEffect, useRef } from 'react';
import animationData from './animation.json';

function LottieAnimation() {
  const container = useRef();

  useEffect(() => {
    const anim = Lottie.loadAnimation({
      container: container.current,
      renderer: 'svg',
      loop: true,
      autoplay: true,
      animationData,
    });

    return () => anim.destroy();
  }, []);

  return <div ref={container} />;
}

// 或使用 React 组件
import { Player } from '@lottiefiles/react-lottie-player';

<Player
  autoplay
  loop
  src="https://assets10.lottiefiles.com/..."
  style={{ height: '300px', width: '300px' }}
/>
```

**适用场景**
- ✅ 设计师用 After Effects 制作动画
- ✅ 复杂的插画动画
- ✅ 品牌动画（Logo、加载动画）
- ✅ 需要设计-开发协作流程

---

## 🎯 图标方案详解

### 1. Iconify（主力，推荐）

**安装**
```bash
npm install @iconify/react
```

**使用示例**
```jsx
import { Icon } from '@iconify/react';

// 基础使用
<Icon icon="mdi:home" />
<Icon icon="mdi-light:home" />
<Icon icon="material-symbols:home" />

// 离线模式（推荐生产环境）
import Icon from '@iconify/react/offline';

// 大小和颜色
<Icon icon="mdi:home" width="24" height="24" />
<Icon icon="mdi:home" style={{ fontSize: '32px', color: '#f00' }} />

// 动画图标
<Icon icon="mdi:loading" className={styles.spin} />

// 条件渲染
<Icon icon={isOnline ? 'mdi:wifi' : 'mdi:wifi-off'} />

// 常用图标集示例
<Icon icon="mdi:home" />              // Material Design Icons
<Icon icon="material-symbols:home" /> // Material Symbols
<Icon icon="lucide:home" />           // Lucide
<Icon icon="tabler:home" />           // Tabler Icons
<Icon icon="ph:house" />              // Phosphor Icons
<Icon icon="heroicons:home" />        // Heroicons
<Icon icon="ri:home-line" />          // Remix Icon
```

**搜索图标**
- 访问 https://icones.js.org/
- 搜索 200,000+ 图标
- 点击复制图标名称

**按需打包配置（Vite）**
```javascript
// vite.config.ts
import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

export default defineConfig({
  plugins: [react()],
  optimizeDeps: {
    include: ['@iconify/react'],
  },
});
```

---

### 2. Lucide React（高质量补充）

**安装**
```bash
npm install lucide-react
```

**使用示例**
```jsx
import { Home, User, Settings, Search } from 'lucide-react';

// 直接使用组件
<Home className={styles.icon} />
<User size={24} strokeWidth={2} />

// 颜色和大小
<Settings
  size={32}
  className="text-blue-500"
/>

// 所有图标
// https://lucide.dev/icons/
```

**图标选择**
- 🌟 1000+ 精心设计的图标
- 🎨 一致的 2x2 网格设计
- 📦 Tree-shakeable
- 🇹🇸 TypeScript 完美支持

---

## 🏗️ 完整项目配置

### package.json 依赖

```json
{
  "dependencies": {
    "react": "^18.3.0",
    "react-dom": "^18.3.0",
    "react-router-dom": "^6.22.0",

    "framer-motion": "^11.0.0",
    "@iconify/react": "^4.1.1",
    "lucide-react": "^0.344.0",

    "clsx": "^2.1.0",
    "tailwind-merge": "^2.2.0",

    "zustand": "^4.5.0",
    "@tanstack/react-query": "^5.28.0",

    "axios": "^1.6.7"
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
    "eslint": "^8.57.0",
    "eslint-plugin-react": "^7.34.0",
    "prettier": "^3.2.5"
  }
}
```

### Tailwind CSS 配置

```javascript
// tailwind.config.js
/** @type {import('tailwindcss').Config} */
export default {
  darkMode: ['class'],
  content: [
    './index.html',
    './src/**/*.{js,ts,jsx,tsx}',
  ],
  theme: {
    extend: {
      colors: {
        border: 'hsl(var(--border))',
        background: 'hsl(var(--background))',
        foreground: 'hsl(var(--foreground))',
        primary: {
          DEFAULT: 'hsl(var(--primary))',
          foreground: 'hsl(var(--primary-foreground))',
        },
      },
      borderRadius: {
        lg: 'var(--radius)',
        md: 'calc(var(--radius) - 2px)',
        sm: 'calc(var(--radius) - 4px)',
      },
      keyframes: {
        'accordion-down': {
          from: { height: '0' },
          to: { height: 'var(--radix-accordion-content-height)' },
        },
        'accordion-up': {
          from: { height: 'var(--radix-accordion-content-height)' },
          to: { height: '0' },
        },
      },
      animation: {
        'accordion-down': 'accordion-down 0.2s ease-out',
        'accordion-up': 'accordion-up 0.2s ease-out',
      },
    },
  },
  plugins: [require('tailwindcss-animate')],
};
```

### Vite 配置

```javascript
// vite.config.ts
import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import path from 'path';

export default defineConfig({
  plugins: [react()],
  resolve: {
    alias: {
      '@': path.resolve(__dirname, './src'),
    },
  },
  server: {
    port: 6677,
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
});
```

---

## 📝 实用组件示例

### 1. 带动画的按钮组件

```tsx
// components/ui/button.tsx
import { motion } from 'framer-motion';
import { cn } from '@/lib/utils';

interface ButtonProps extends React.ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: 'default' | 'outline' | 'ghost';
  size?: 'sm' | 'md' | 'lg';
}

export const Button = motion.create(
  ({
    className,
    variant = 'default',
    size = 'md',
    children,
    ...props
  }: ButtonProps) => {
    return (
      <motion.button
        whileHover={{ scale: 1.02 }}
        whileTap={{ scale: 0.98 }}
        className={cn(
          'rounded-lg font-medium transition-colors',
          {
            'bg-primary text-primary-foreground hover:bg-primary/90':
              variant === 'default',
            'border border-input bg-background hover:bg-accent':
              variant === 'outline',
            'hover:bg-accent hover:text-accent-foreground':
              variant === 'ghost',
          },
          {
            'h-9 px-3 text-sm': size === 'sm',
            'h-10 px-4': size === 'md',
            'h-11 px-8 text-lg': size === 'lg',
          },
          className
        )}
        {...props}
      >
        {children}
      </motion.button>
    );
  }
);
```

### 2. 页面过渡组件

```tsx
// components/page-transition.tsx
import { motion, AnimatePresence } from 'framer-motion';
import { useLocation } from 'react-router-dom';

const pageVariants = {
  initial: { opacity: 0, y: 20 },
  enter: { opacity: 1, y: 0 },
  exit: { opacity: 0, y: -20 },
};

const pageTransition = {
  type: 'tween',
  ease: 'anticipate',
  duration: 0.5,
};

export function PageTransition({ children }: { children: React.ReactNode }) {
  const location = useLocation();

  return (
    <AnimatePresence mode="wait">
      <motion.div
        key={location.pathname}
        initial="initial"
        animate="enter"
        exit="exit"
        variants={pageVariants}
        transition={pageTransition}
      >
        {children}
      </motion.div>
    </AnimatePresence>
  );
}
```

### 3. 图标按钮组件

```tsx
// components/ui/icon-button.tsx
import { Icon } from '@iconify/react';
import { motion } from 'framer-motion';
import { cn } from '@/lib/utils';

interface IconButtonProps {
  icon: string;
  label?: string;
  onClick?: () => void;
  className?: string;
}

export function IconButton({
  icon,
  label,
  onClick,
  className,
}: IconButtonProps) {
  return (
    <motion.button
      whileHover={{ scale: 1.1, rotate: 5 }}
      whileTap={{ scale: 0.9 }}
      onClick={onClick}
      className={cn(
        'p-2 rounded-lg hover:bg-accent transition-colors',
        className
      )}
      aria-label={label}
    >
      <Icon icon={icon} className="w-5 h-5" />
    </motion.button>
  );
}

// 使用
<IconButton icon="mdi:home" label="首页" onClick={() => navigate('/')} />
<IconButton icon="mdi:cog" label="设置" />
<IconButton icon="lucide:moon" label="切换主题" />
```

### 4. 加载动画组件

```tsx
// components/ui/loading.tsx
import { Icon } from '@iconify/react';
import { motion } from 'framer-motion';

export function Loading({ size = 'md' }: { size?: 'sm' | 'md' | 'lg' }) {
  const sizeMap = {
    sm: 'w-4 h-4',
    md: 'w-8 h-8',
    lg: 'w-12 h-12',
  };

  return (
    <motion.div
      initial={{ opacity: 0 }}
      animate={{ opacity: 1 }}
      exit={{ opacity: 0 }}
    >
      <Icon
        icon="mdi:loading"
        className={cn(sizeMap[size], 'animate-spin')}
      />
    </motion.div>
  );
}
```

---

## 🎨 动效最佳实践

### 1. 性能优化

```jsx
// ✅ 使用 transform 和 opacity（GPU 加速）
<motion.div
  animate={{ x: 100, opacity: 0.5 }}  // 好
/>

// ❌ 避免使用 width/height/top/left（触发重排）
<motion.div
  animate={{ width: 100, left: 50 }}  // 差
/>

// ✅ 使用 will-change 提示浏览器
<motion.div
  style={{ willChange: 'transform, opacity' }}
  animate={{ scale: 1.1 }}
/>
```

### 2. 动画编排

```jsx
// ✅ 使用 delay 实现交错动画
{items.map((item, i) => (
  <motion.div
    key={item.id}
    initial={{ opacity: 0, y: 20 }}
    animate={{ opacity: 1, y: 0 }}
    transition={{ delay: i * 0.1 }}  // 交错
  />
))}

// ✅ 使用 staggerChildren（子元素交错）
const container = {
  hidden: { opacity: 0 },
  show: {
    opacity: 1,
    transition: {
      staggerChildren: 0.1
    }
  }
};

const item = {
  hidden: { opacity: 0 },
  show: { opacity: 1 }
};

<motion.ul variants={container} initial="hidden" animate="show">
  {items.map(item => (
    <motion.li key={item.id} variants={item} />
  ))}
</motion.ul>
```

### 3. 响应式动画

```jsx
// 根据屏幕尺寸调整动画
<motion.div
  animate={{
    x: isMobile ? 50 : 100,
    scale: isMobile ? 1.1 : 1.2
  }}
/>

// 使用 viewport 限制动画范围
<motion.div
  initial="hidden"
  whileInView="visible"
  viewport={{ once: true, margin: "-100px" }}
  variants={{
    hidden: { opacity: 0 },
    visible: { opacity: 1 }
  }}
/>
```

---

## 📚 工具和资源

### 图标搜索
- **Icones** - https://icones.js.org/ （搜索所有图标集）
- **Lucide** - https://lucide.dev/icons/
- **Phosphor** - https://phosphoricons.com/
- **Heroicons** - https://heroicons.com/

### 动效灵感
- **Framer Motion Examples** - https://www.framer.com/motion/
- **GSAP Showcase** - https://gsap.com/showcase/
- **LottieFiles** - https://lottiefiles.com/

### 配色方案
- **Coolors** - https://coolors.co/
- **Adobe Color** - https://color.adobe.com/

### 字体推荐
```css
/* 标题字体 - 有个性 */
font-family: 'Space Grotesk', 'Outfit', 'Clash Display';

/* 正文字体 - 易读 */
font-family: 'Inter', 'Geist', 'Plus Jakarta Sans';

/* 代码字体 */
font-family: 'Fira Code', 'JetBrains Mono';
```

---

## 🚀 快速启动模板

```bash
# 创建 Vite + React + TypeScript 项目
npm create vite@latest fly-agent-web -- --template react-ts

# 安装核心依赖
cd fly-agent-web
npm install framer-motion @iconify/react lucide-react
npm install clsx tailwind-merge

# 安装 Tailwind CSS
npm install -D tailwindcss postcss autoprefixer
npx tailwindcss init -p

# 安装其他依赖
npm install react-router-dom zustand @tanstack/react-query
npm install -D @types/node

# 启动开发服务器
npm run dev
```

---

## 📖 后续阅读

- **Framer Motion 官方文档**: https://www.framer.com/motion/
- **GSAP 官方文档**: https://gsap.com/docs/
- **Tailwind CSS 官方文档**: https://tailwindcss.com/docs
- **React Router 官方文档**: https://reactrouter.com/
- **Iconify 图标搜索**: https://icones.js.org/

---

## 📝 更新日志

| 版本 | 日期 | 更新内容 |
|------|------|---------|
| v1.0 | 2025-02-05 | 初始版本，定义核心栈和最佳实践 |

---

> **维护者**: Fly Agent Team
> **反馈**: 如有问题或建议，请提交 Issue
