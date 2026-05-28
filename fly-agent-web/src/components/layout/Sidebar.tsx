import { motion, AnimatePresence } from 'framer-motion'
import { Icon } from '@iconify/react'
import { useChatStore } from '@/store/chatStore'
import { cn } from '@/lib/utils'
import { formatTimestamp } from '@/lib/utils'
import { Link } from 'react-router-dom'

export function Sidebar() {
  const {
    conversations,
    currentConversationId,
    isSidebarOpen,
    switchConversation,
    createConversation,
    deleteConversation,
  } = useChatStore()

  return (
    <motion.aside
      animate={{ width: isSidebarOpen ? 280 : 0 }}
      transition={{ type: 'spring', stiffness: 300, damping: 30 }}
      className="overflow-hidden bg-white/80 backdrop-blur-lg border-r border-terminal flex-shrink-0 relative z-20"
    >
      <AnimatePresence mode="wait">
        {isSidebarOpen && (
          <motion.div
            initial={{ opacity: 0 }}
            animate={{ opacity: 1 }}
            exit={{ opacity: 0 }}
            transition={{ duration: 0.2 }}
            className="w-[280px] h-full flex flex-col"
          >
            {/* Logo 和标题 */}
            <div className="p-4 border-b border-terminal">
              <div className="flex items-center gap-3 mb-4">
                <div className="w-10 h-10 rounded-lg bg-gradient-to-br from-cyan to-green flex items-center justify-center shadow-lg shadow-cyan/20">
                  <Icon icon="mdi:robot" className="w-6 h-6 text-white" />
                </div>
                <div>
                  <h1 className="font-bold text-text-primary text-lg font-mono">Fly Agent</h1>
                  <p className="text-xs text-text-secondary font-mono">AI 智能体平台</p>
                </div>
              </div>

              {/* 新建会话按钮 */}
              <motion.button
                whileHover={{ scale: 1.02 }}
                whileTap={{ scale: 0.98 }}
                onClick={() => createConversation()}
                className="w-full py-3 px-4 rounded-lg bg-gradient-to-r from-cyan to-green text-white font-bold font-mono text-sm shadow-lg shadow-cyan/20 hover:shadow-xl hover:shadow-cyan/30 transition-all duration-300 flex items-center justify-center gap-2"
              >
                <Icon icon="mdi:plus" className="w-5 h-5" />
                新建对话
              </motion.button>
            </div>

            <div className="border-b border-terminal p-3">
              <p className="mb-2 px-1 text-xs font-bold text-text-muted">工作区</p>
              <div className="space-y-1">
                <WorkspaceLink
                  icon="mdi:message-processing-outline"
                  label="智能对话"
                  to="/"
                  active
                />
                <WorkspaceLink
                  icon="mdi:source-branch-sync"
                  label="SWE-Pro 流水线"
                  to="/swe"
                />
              </div>
            </div>

            {/* 会话列表 */}
            <div className="flex-1 overflow-y-auto custom-scrollbar p-3">
              {conversations.length === 0 ? (
                <div className="text-center py-8">
                  <Icon
                    icon="mdi:message-outline"
                    className="w-12 h-12 mx-auto text-text-muted mb-2"
                  />
                  <p className="text-sm text-text-muted font-mono">暂无对话</p>
                </div>
              ) : (
                conversations.map((conv) => (
                  <motion.div
                    key={conv.id}
                    whileHover={{ x: 4 }}
                    whileTap={{ scale: 0.98 }}
                    onClick={() => switchConversation(conv.id)}
                    className={cn(
                      'relative group p-3 mb-2 rounded-lg cursor-pointer transition-all duration-200 font-mono',
                      currentConversationId === conv.id
                        ? 'bg-gradient-to-r from-cyan/10 to-green/10 border-2 border-cyan text-cyan'
                        : 'hover:bg-tertiary/50 border-2 border-transparent'
                    )}
                  >
                    <div className="flex items-start gap-3">
                      <Icon
                        icon="mdi:message-text"
                        className={cn(
                          'w-5 h-5 flex-shrink-0 mt-0.5',
                          currentConversationId === conv.id
                            ? 'text-cyan'
                            : 'text-text-secondary'
                        )}
                      />
                      <div className="flex-1 min-w-0">
                        <h3
                          className={cn(
                            'font-medium truncate mb-1 text-sm',
                            currentConversationId === conv.id
                              ? 'text-cyan'
                              : 'text-text-primary'
                          )}
                        >
                          {conv.title}
                        </h3>
                        <div
                          className={cn(
                            'flex items-center gap-2 text-xs',
                            currentConversationId === conv.id
                              ? 'text-text-secondary'
                              : 'text-text-muted'
                          )}
                        >
                          <span>{conv.messageCount} 条消息</span>
                          <span>•</span>
                          <span>{formatTimestamp(conv.updatedAt)}</span>
                        </div>
                      </div>
                    </div>

                    {/* 删除按钮 */}
                    <motion.button
                      initial={{ opacity: 0 }}
                      whileHover={{ opacity: 1 }}
                      onClick={(e) => {
                        e.stopPropagation()
                        if (confirm('确定要删除这个对话吗?')) {
                          deleteConversation(conv.id)
                        }
                      }}
                      className={cn(
                        'absolute right-2 top-1/2 -translate-y-1/2 p-2 rounded transition-opacity',
                        currentConversationId === conv.id
                          ? 'hover:bg-cyan/20'
                          : 'opacity-0 group-hover:opacity-100 hover:bg-error/20'
                      )}
                    >
                      <Icon
                        icon="mdi:delete"
                        className={cn(
                          'w-4 h-4',
                          currentConversationId === conv.id
                            ? 'text-cyan'
                            : 'text-error'
                        )}
                      />
                    </motion.button>
                  </motion.div>
                ))
              )}
            </div>

            {/* 底部信息 */}
            <div className="p-4 border-t border-terminal">
              <div className="flex items-center gap-2 text-xs text-text-muted font-mono">
                <Icon icon="mdi:information" className="w-4 h-4 text-text-secondary" />
                <span>Powered by AgentScope</span>
              </div>
            </div>
          </motion.div>
        )}
      </AnimatePresence>
    </motion.aside>
  )
}

function WorkspaceLink({
  icon,
  label,
  to,
  active = false,
}: {
  icon: string
  label: string
  to: string
  active?: boolean
}) {
  return (
    <Link
      to={to}
      className={cn(
        'flex w-full items-center gap-3 rounded-lg border px-3 py-2 text-left text-sm font-bold transition-colors',
        active
          ? 'border-cyan bg-primary-50 text-cyan'
          : 'border-transparent text-text-secondary hover:bg-tertiary/50 hover:text-text-primary'
      )}
    >
      <Icon icon={icon} className="h-5 w-5 flex-shrink-0" />
      <span>{label}</span>
    </Link>
  )
}
