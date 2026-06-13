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
    <div className="bg-android-surface border border-android-border rounded-xl p-4">
      <div className="flex items-start justify-between mb-3">
        <div className={`${colorMap[color]} opacity-80`}>{icon}</div>
        <span className="text-xs text-android-muted">{label}</span>
      </div>
      <div className={`text-xl font-bold ${colorMap[color]} mb-0.5`}>{value}</div>
      {sub && <p className="text-xs text-android-muted">{sub}</p>}
      {bar !== undefined && (
        <div className="mt-3">
          <div className="w-full h-1.5 bg-android-border rounded-full overflow-hidden">
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
