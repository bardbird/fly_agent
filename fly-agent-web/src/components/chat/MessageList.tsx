import { motion, AnimatePresence } from 'framer-motion'
import { useEffect, useRef } from 'react'
import { MessageItem } from './MessageItem'
import { useChatStore } from '@/store/chatStore'

export function MessageList() {
  const { currentConversationId, messages, isStreaming } = useChatStore()
  const messagesEndRef = useRef<HTMLDivElement>(null)

  const currentMessages = currentConversationId
    ? messages[currentConversationId] || []
    : []

  // 自动滚动到底部
  useEffect(() => {
    messagesEndRef.current?.scrollIntoView({ behavior: 'smooth' })
  }, [currentMessages, isStreaming])

  return (
    <div className="flex-1 overflow-y-auto custom-scrollbar p-4">
      {currentMessages.length === 0 ? (
        <div className="flex flex-col items-center justify-center h-full">
          <motion.div
            initial={{ opacity: 0, y: 20 }}
            animate={{ opacity: 1, y: 0 }}
            transition={{ duration: 0.5 }}
            className="text-center"
          >
            <div className="w-16 h-16 mx-auto mb-4 rounded-full bg-gradient-to-br from-cyan to-green flex items-center justify-center shadow-lg shadow-cyan/20">
              <svg
                className="w-8 h-8 text-white"
                fill="none"
                stroke="currentColor"
                viewBox="0 0 24 24"
              >
                <path
                  strokeLinecap="round"
                  strokeLinejoin="round"
                  strokeWidth={2}
                  d="M8 10h.01M12 10h.01M16 10h.01M9 16H5a2 2 0 01-2-2V6a2 2 0 012-2h14a2 2 0 012 2v8a2 2 0 01-2 2h-5l-5 5v-5z"
                />
              </svg>
            </div>
            <h3 className="text-xl font-semibold text-cyan mb-2 font-mono">
              开始新对话
            </h3>
            <p className="text-sm text-text-secondary font-mono">
              向 Fly Agent 发送消息开始对话
            </p>
          </motion.div>
        </div>
      ) : (
        <AnimatePresence mode="popLayout">
          {currentMessages.map((msg) => (
            <MessageItem key={msg.id} message={msg} />
          ))}
        </AnimatePresence>
      )}

      <div ref={messagesEndRef} />
    </div>
  )
}
