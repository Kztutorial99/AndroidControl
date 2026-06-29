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
    <div className="bg-android-surface border border-android-border rounded-xl p-2.5 md:p-4">
      <div className="flex items-start justify-between mb-1.5 md:mb-3">
        <div className={`${colorMap[color]} opacity-80 scale-90 md:scale-100 origin-left`}>{icon}</div>
        <span className="text-[10px] md:text-xs text-android-muted truncate ml-1">{label}</span>
      </div>
      <div className={`text-sm md:text-xl font-bold ${colorMap[color]} leading-tight truncate`}>{value}</div>
      {sub && <p className="text-[10px] md:text-xs text-android-muted mt-0.5 leading-tight truncate">{sub}</p>}
      {bar !== undefined && (
        <div className="mt-2 md:mt-3">
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
