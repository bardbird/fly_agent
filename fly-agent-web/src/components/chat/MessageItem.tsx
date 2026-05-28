import { motion } from 'framer-motion'
import ReactMarkdown from 'react-markdown'
import remarkGfm from 'remark-gfm'
import rehypeHighlight from 'rehype-highlight'
import type { Message } from '@/types/chat'
import { cn } from '@/lib/utils'

interface MessageItemProps {
  message: Message
}

export function MessageItem({ message }: MessageItemProps) {
  const isUser = message.role === 'user'
  const isEmpty = !message.content || message.content.length === 0

  return (
    <motion.div
      initial={{ opacity: 0, y: 10, scale: 0.95 }}
      animate={{ opacity: 1, y: 0, scale: 1 }}
      transition={{ duration: 0.3 }}
      className={cn('flex mb-4', isUser ? 'justify-end' : 'justify-start')}
    >
      <div
        className={cn(
          'max-w-[80%] rounded-lg',
          isUser
            ? 'bg-gradient-to-br from-cyan to-green text-white rounded-br-sm px-4 py-3'
            : cn(
                'terminal border border-terminal rounded-bl-sm',
                isEmpty ? 'min-w-[4.5rem] min-h-[2.5rem] px-4 py-3' : 'px-4 py-3'
              )
        )}
      >
        {isUser ? (
          <p className="whitespace-pre-wrap break-words leading-relaxed font-mono text-sm">
            {message.content}
          </p>
        ) : (
          <div className="prose prose-sm max-w-none whitespace-pre-wrap break-words text-sm">
            <ReactMarkdown
              remarkPlugins={[remarkGfm]}
              rehypePlugins={[rehypeHighlight]}
              components={{
                p: ({ children }) => <p className="mb-3 last:mb-0 text-text-primary font-normal leading-7">{children}</p>,
                ul: ({ children }) => <ul className="list-disc list-inside mb-3 text-text-primary">{children}</ul>,
                ol: ({ children }) => <ol className="list-decimal list-inside mb-3 text-text-primary">{children}</ol>,
                li: ({ children }) => <li className="mb-1 text-text-primary leading-6">{children}</li>,
                code: ({ className, children }) => {
                  const isInline = !className
                  if (isInline) {
                    return (
                      <code className="px-1.5 py-0.5 rounded bg-primary-100 text-cyan text-xs font-mono">
                        {children}
                      </code>
                    )
                  }
                  return (
                    <code className={cn('block p-3 rounded bg-primary text-green text-xs font-mono overflow-x-auto', className)}>
                      {children}
                    </code>
                  )
                },
                pre: ({ children }) => <pre className="mb-3 overflow-x-auto whitespace-pre-wrap leading-6">{children}</pre>,
                h1: ({ children }) => <h1 className="text-lg font-semibold mb-3 text-cyan leading-7">{children}</h1>,
                h2: ({ children }) => <h2 className="text-base font-semibold mb-3 text-green leading-7">{children}</h2>,
                h3: ({ children }) => <h3 className="text-sm font-semibold mb-2 text-purple leading-6">{children}</h3>,
                blockquote: ({ children }) => (
                  <blockquote className="border-l-4 border-purple pl-4 italic my-3 text-text-secondary leading-6">
                    {children}
                  </blockquote>
                ),
              }}
            >
              {message.content}
            </ReactMarkdown>
          </div>
        )}
      </div>
    </motion.div>
  )
}
