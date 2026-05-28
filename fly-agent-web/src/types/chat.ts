export interface Message {
  id: string
  role: 'user' | 'assistant' | 'system'
  content: string
  timestamp: number
  status?: 'sending' | 'success' | 'failed' | 'streaming'
}

export interface Conversation {
  id: string
  title: string
  createdAt: number
  updatedAt: number
  messageCount: number
}

export interface SendMessageRequest {
  conversationId: string | null
  message: string
}

export interface SendMessageResponse {
  messageId: string
  content: string
}
