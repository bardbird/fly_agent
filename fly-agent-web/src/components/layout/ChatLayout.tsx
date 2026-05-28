import { Sidebar } from './Sidebar'
import { MessageList } from '../chat/MessageList'
import { InputArea } from '../chat/InputArea'
import { Icon } from '@iconify/react'
import { useChatStore } from '@/store/chatStore'
import { motion } from 'framer-motion'

export function ChatLayout() {
  const { toggleSidebar } = useChatStore()

  return (
    <div className="flex h-screen bg-primary overflow-hidden">
      <Sidebar />

      {/* 主聊天区域 */}
      <div className="flex-1 flex flex-col relative z-10">
        {/* 顶部栏 */}
        <div className="h-16 border-b border-terminal bg-white/80 backdrop-blur-lg flex items-center px-4">
          <motion.button
            whileHover={{ scale: 1.05 }}
            whileTap={{ scale: 0.95 }}
            onClick={toggleSidebar}
            className="p-2 rounded-lg hover:bg-tertiary/50 transition-all duration-200"
          >
            <Icon icon="mdi:menu" className="w-6 h-6 text-cyan" />
          </motion.button>

          <div className="ml-4 flex-1">
            <h1 className="text-lg font-bold text-text-primary font-mono">Fly Agent</h1>
            <p className="text-xs text-text-secondary font-mono">AI 智能助手</p>
          </div>

          {/* 右侧工具栏 */}
          <div className="flex items-center gap-2">
            <motion.button
              whileHover={{ scale: 1.05 }}
              whileTap={{ scale: 0.95 }}
              className="p-2 rounded-lg hover:bg-tertiary/50 transition-all duration-200"
              title="设置"
            >
              <Icon icon="mdi:cog" className="w-5 h-5 text-cyan" />
            </motion.button>
          </div>
        </div>

        {/* 消息列表 */}
        <MessageList />

        {/* 输入区域 */}
        <InputArea />
      </div>
    </div>
  )
}
