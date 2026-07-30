interface StatCardProps {
  label: string
  value: string
  sub?: string
  icon: React.ReactNode
  color?: 'green' | 'blue' | 'yellow' | 'red' | 'default'
  bar?: number
}

const colorMap = {
  green: 'text-android-green',
  blue: 'text-android-blue',
  yellow: 'text-android-yellow',
  red: 'text-android-red',
  default: 'text-android-text',
}

const barColorMap = {
  green: 'bg-android-green',
  blue: 'bg-android-blue',
  yellow: 'bg-android-yellow',
  red: 'bg-android-red',
  default: 'bg-android-muted',
}

export default function StatCard({ label, value, sub, icon, color = 'default', bar }: StatCardProps) {
  return (
    <div className="bg-android-surface border border-android-border rounded-xl p-2 md:p-4">
      <div className="flex items-center justify-between mb-1 md:mb-3">
        <div className={`${colorMap[color]} opacity-80`}>{icon}</div>
        <span className="text-[9px] md:text-xs text-android-muted truncate ml-1 max-w-[60%] md:max-w-none text-right">{label}</span>
      </div>
      <div className={`text-[13px] md:text-xl font-bold ${colorMap[color]} leading-tight whitespace-nowrap overflow-hidden text-ellipsis`}>{value}</div>
      {sub && (
        <p className="text-[8px] md:text-xs text-android-muted mt-0.5 leading-tight whitespace-nowrap overflow-hidden text-ellipsis">
          {sub}
        </p>
      )}
      {bar !== undefined && (
        <div className="mt-1.5 md:mt-3">
          <div className="w-full h-1 md:h-1.5 bg-android-border rounded-full overflow-hidden">
            <div
              className={`h-full rounded-full transition-all duration-500 ${barColorMap[color]}`}
              style={{ width: `${Math.min(bar, 100)}%` }}
            />
          </div>
        </div>
      )}
    </div>
  )
}
