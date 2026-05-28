import { create } from 'zustand'
import { persist } from 'zustand/middleware'
import type { Message, Conversation } from '@/types/chat'
import { sendMessageStream } from '@/lib/api'

interface ChatStore {
  // 状态
  conversations: Conversation[]
  currentConversationId: string | null
  messages: Record<string, Message[]>
  isSidebarOpen: boolean
  isStreaming: boolean
  streamingMessageId: string | null

  // Actions - 会话管理
  createConversation: (title?: string) => string
  deleteConversation: (id: string) => void
  switchConversation: (id: string) => void
  updateConversationTitle: (id: string, title: string) => void

  // Actions - 消息操作
  sendMessage: (content: string) => Promise<void>
  editMessage: (msgId: string, newContent: string) => Promise<void>
  deleteMessage: (msgId: string) => void

  // Actions - UI 控制
  toggleSidebar: () => void
  setStreaming: (isStreaming: boolean, msgId?: string) => void
}

export const useChatStore = create<ChatStore>()(
  persist(
    (set, get) => ({
      // 初始状态
      conversations: [],
      currentConversationId: null,
      messages: {},
      isSidebarOpen: true,
      isStreaming: false,
      streamingMessageId: null,

      // 会话管理
      createConversation: (title) => {
        const id = `conv-${Date.now()}`
        const newConv: Conversation = {
          id,
          title: title || '新对话',
          createdAt: Date.now(),
          updatedAt: Date.now(),
          messageCount: 0,
        }

        set((state) => ({
          conversations: [newConv, ...state.conversations],
          currentConversationId: id,
          messages: {
            ...state.messages,
            [id]: [],
          },
        }))

        return id
      },

      deleteConversation: (id) => {
        set((state) => {
          const newConversations = state.conversations.filter((c) => c.id !== id)
          const newMessages = Object.fromEntries(
            Object.entries(state.messages).filter(([key]) => key !== id)
          )

          return {
            conversations: newConversations,
            messages: newMessages,
            currentConversationId:
              state.currentConversationId === id
                ? newConversations[0]?.id || null
                : state.currentConversationId,
          }
        })
      },

      switchConversation: (id) => {
        set({ currentConversationId: id })
      },

      updateConversationTitle: (id, title) => {
        set((state) => ({
          conversations: state.conversations.map((c) =>
            c.id === id ? { ...c, title } : c
          ),
        }))
      },

      // 消息操作
      sendMessage: async (content) => {
        const { currentConversationId, createConversation } = get()

        let conversationId = currentConversationId

        // 如果没有当前会话,自动创建
        if (!conversationId) {
          conversationId = createConversation()
          set({ currentConversationId: conversationId })
        }

        const userMsg: Message = {
          id: `msg-${Date.now()}`,
          role: 'user',
          content,
          timestamp: Date.now(),
          status: 'sending',
        }

        // 乐观更新 - 添加用户消息
        set((state) => ({
          messages: {
            ...state.messages,
            [conversationId!]: [
              ...(state.messages[conversationId!] || []),
              userMsg,
            ],
          },
        }))

        // 创建 AI 消息占位符
        const aiMsg: Message = {
          id: `msg-ai-${Date.now()}`,
          role: 'assistant',
          content: '',
          timestamp: Date.now(),
          status: 'streaming',
        }

        set((state) => ({
          messages: {
            ...state.messages,
            [conversationId!]: [
              ...state.messages[conversationId!],
              aiMsg,
            ],
          },
          isStreaming: true,
          streamingMessageId: aiMsg.id,
        }))

        try {
          let aiContent = ''

          // 流式接收
          await sendMessageStream(
            {
              conversationId: conversationId!,
              message: content,
            },
            (chunk, isFullContent) => {
              if (isFullContent) {
                // 完整内容：替换整个消息内容（修复格式）
                set((state) => ({
                  messages: {
                    ...state.messages,
                    [conversationId!]: state.messages[conversationId!].map((msg) =>
                      msg.id === aiMsg.id ? { ...msg, content: chunk } : msg
                    ),
                  },
                }))
              } else {
                // 增量内容：追加到现有内容（打字机效果）
                aiContent += chunk
                set((state) => ({
                  messages: {
                    ...state.messages,
                    [conversationId!]: state.messages[conversationId!].map((msg) =>
                      msg.id === aiMsg.id ? { ...msg, content: aiContent } : msg
                    ),
                  },
                }))
              }
            },
            () => {
              // 完成
              set((state) => ({
                messages: {
                  ...state.messages,
                  [conversationId!]: state.messages[conversationId!].map((msg) =>
                    msg.id === userMsg.id ? { ...msg, status: 'success' } : msg
                  ),
                },
                conversations: state.conversations.map((c) =>
                  c.id === conversationId
                    ? {
                        ...c,
                        messageCount: (c.messageCount || 0) + 2,
                        updatedAt: Date.now(),
                      }
                    : c
                ),
                isStreaming: false,
                streamingMessageId: null,
              }))
            },
            () => {
              // 错误处理
              set((state) => ({
                messages: {
                  ...state.messages,
                  [conversationId!]: state.messages[conversationId!].map((msg) =>
                    msg.id === userMsg.id ? { ...msg, status: 'failed' } : msg
                  ),
                },
                isStreaming: false,
                streamingMessageId: null,
              }))
            }
          )
        } catch (error) {
          set((state) => ({
            messages: {
              ...state.messages,
              [conversationId!]: state.messages[conversationId!].map((msg) =>
                msg.id === userMsg.id ? { ...msg, status: 'failed' } : msg
              ),
            },
            isStreaming: false,
            streamingMessageId: null,
          }))
        }
      },

      deleteMessage: (msgId) => {
        const { currentConversationId, messages } = get()
        if (!currentConversationId) return

        set({
          messages: {
            ...messages,
            [currentConversationId]: messages[currentConversationId].filter(
              (msg) => msg.id !== msgId
            ),
          },
        })
      },

      editMessage: async (msgId, newContent) => {
        const { currentConversationId, messages } = get()
        if (!currentConversationId) return

        set({
          messages: {
            ...messages,
            [currentConversationId]: messages[currentConversationId].map((msg) =>
              msg.id === msgId ? { ...msg, content: newContent } : msg
            ),
          },
        })
      },

      // UI 控制
      toggleSidebar: () => {
        set((state) => ({ isSidebarOpen: !state.isSidebarOpen }))
      },

      setStreaming: (isStreaming, msgId) => {
        set({ isStreaming, streamingMessageId: msgId })
      },
    }),
    {
      name: 'chat-storage',
      partialize: (state) => ({
        conversations: state.conversations,
        messages: state.messages,
      }),
    }
  )
)
