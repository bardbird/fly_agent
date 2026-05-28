import { Icon } from '@iconify/react'
import { motion } from 'framer-motion'
import { cn } from '@/lib/utils'

interface IconButtonProps {
  icon: string
  label?: string
  onClick?: () => void
  className?: string
  size?: 'sm' | 'md' | 'lg'
}

export function IconButton({
  icon,
  label,
  onClick,
  className,
  size = 'md',
}: IconButtonProps) {
  const sizeMap = {
    sm: 'w-8 h-8',
    md: 'w-10 h-10',
    lg: 'w-12 h-12',
  }

  const iconSizeMap = {
    sm: 'w-4 h-4',
    md: 'w-5 h-5',
    lg: 'w-6 h-6',
  }

  return (
    <motion.button
      whileHover={{ scale: 1.1 }}
      whileTap={{ scale: 0.9 }}
      onClick={onClick}
      className={cn(
        'rounded-lg flex items-center justify-center hover:bg-primary-50 transition-all duration-200',
        sizeMap[size],
        className
      )}
      aria-label={label}
    >
      <Icon icon={icon} className={`${iconSizeMap[size]} text-primary-600`} />
    </motion.button>
  )
}
