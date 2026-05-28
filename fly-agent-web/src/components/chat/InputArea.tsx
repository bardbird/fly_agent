import { useState, useRef, useEffect } from 'react'
import { motion } from 'framer-motion'
import { Icon } from '@iconify/react'
import { useChatStore } from '@/store/chatStore'
import { Loading } from '@/components/ui/loading'

export function InputArea() {
  const [content, setContent] = useState('')
  const textareaRef = useRef<HTMLTextAreaElement>(null)
  const { sendMessage, isStreaming } = useChatStore()

  // 自动调整 textarea 高度
  useEffect(() => {
    if (textareaRef.current) {
      textareaRef.current.style.height = 'auto'
      textareaRef.current.style.height = `${Math.min(textareaRef.current.scrollHeight, 200)}px`
    }
  }, [content])

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault()
    if (!content.trim() || isStreaming) return

    await sendMessage(content.trim())
    setContent('') // 清空输入框
  }

  const handleKeyDown = (e: React.KeyboardEvent) => {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault()
      handleSubmit(e)
    }
  }

  return (
    <div className="border-t border-terminal bg-white/80 backdrop-blur-lg p-4">
      <form onSubmit={handleSubmit} className="max-w-4xl mx-auto">
        <div className="relative flex items-center">
          {isStreaming ? (
            // 加载状态：显示圆点动画
            <div className="w-full resize-none rounded-lg border border-terminal bg-secondary px-4 py-3 flex items-center gap-2 min-h-[48px]">
              <Loading size="xs" />
              <span className="text-xs text-text-muted font-mono">AI 正在思考...</span>
            </div>
          ) : (
            <>
              <textarea
                ref={textareaRef}
                value={content}
                onChange={(e) => setContent(e.target.value)}
                onKeyDown={handleKeyDown}
                placeholder="输入消息... (Enter 发送, Shift+Enter 换行)"
                className="w-full resize-none rounded-lg border border-terminal bg-secondary px-4 py-3 pr-24 text-sm text-text-primary placeholder:text-sm placeholder:text-text-muted font-mono focus:border-cyan focus:outline-none transition-all duration-300 min-h-[48px] max-h-[200px] overflow-y-auto box-border"
                rows={1}
              />

              {/* 清空按钮 */}
              {content && (
                <motion.button
                  initial={{ opacity: 0, scale: 0.8 }}
                  animate={{ opacity: 1, scale: 1 }}
                  exit={{ opacity: 0, scale: 0.8 }}
                  onClick={() => setContent('')}
                  className="absolute right-14 top-1/2 -translate-y-1/2 p-2 rounded-full hover:bg-tertiary/50 transition-colors"
                  type="button"
                >
                  <Icon icon="mdi:close" className="w-4 h-4 text-text-muted" />
                </motion.button>
              )}

              {/* 发送按钮 */}
              <motion.button
                whileHover={{ scale: 1.05 }}
                whileTap={{ scale: 0.95 }}
                type="submit"
                disabled={!content.trim()}
                className="absolute right-2 top-1/2 -translate-y-1/2 w-10 h-10 rounded-lg bg-gradient-to-br from-cyan to-green text-white shadow-lg shadow-cyan/20 hover:shadow-xl hover:shadow-cyan/30 disabled:opacity-50 disabled:cursor-not-allowed transition-all duration-300 flex items-center justify-center font-mono text-xs font-bold"
              >
                <Icon icon="mdi:send" className="w-5 h-5" />
              </motion.button>
            </>
          )}
        </div>
      </form>

      <div className="text-center mt-2">
        <p className="text-xs text-text-muted font-mono">
          AI 生成的内容可能不准确,请谨慎参考
        </p>
      </div>
    </div>
  )
}
