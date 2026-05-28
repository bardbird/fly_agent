import { motion } from 'framer-motion'
import { cn } from '@/lib/utils'
import type { HTMLMotionProps } from 'framer-motion'

interface ButtonProps extends Omit<HTMLMotionProps<'button'>, 'whileHover' | 'whileTap'> {
  variant?: 'default' | 'outline' | 'ghost'
  size?: 'sm' | 'md' | 'lg'
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
          'rounded-lg font-bold font-mono transition-all duration-300',
          {
            'bg-gradient-to-br from-cyan to-green text-white shadow-lg shadow-cyan/20 hover:shadow-xl hover:shadow-cyan/30':
              variant === 'default',
            'border border-cyan bg-transparent text-cyan hover:bg-cyan/10':
              variant === 'outline',
            'hover:bg-tertiary/50 text-text-primary': variant === 'ghost',
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
    )
  }
)
