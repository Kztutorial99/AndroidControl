'use client'
import { useState, useEffect, Suspense } from 'react'
import { useRouter, useSearchParams } from 'next/navigation'

function LoginForm() {
  const [password, setPassword] = useState('')
  const [error, setError] = useState('')
  const [loading, setLoading] = useState(false)
  const [dots, setDots] = useState(0)
  const router = useRouter()
  const searchParams = useSearchParams()
  const from = searchParams.get('from') || '/'

  useEffect(() => {
    const interval = setInterval(() => setDots(d => (d + 1) % 4), 500)
    return () => clearInterval(interval)
  }, [])

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault()
    setLoading(true)
    setError('')
    try {
      const res = await fetch('/api/auth/login', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ password }),
      })
      if (res.ok) {
        router.push(from)
        router.refresh()
      } else {
        setError('[ \u26a0 ACCESS DENIED \u2014 INVALID PASSWORD ]')
        setPassword('')
      }
    } catch {
      setError('[ \u26a0 CONNECTION ERROR ]')
    } finally {
      setLoading(false)
    }
  }

  const loadingText = '[ AUTHENTICATING' + '.'.repeat(dots) + ' ]'

  return (
    <div className="min-h-screen flex items-center justify-center p-4"
      style={{ fontFamily: 'monospace', background: 'linear-gradient(135deg,#020502 0%,#030903 100%)' }}>
      <div className="fixed inset-0 pointer-events-none" style={{
        backgroundImage: 'repeating-linear-gradient(0deg,transparent,transparent 3px,rgba(0,255,65,0.02) 3px,rgba(0,255,65,0.02) 4px)',
        zIndex: 0
      }} />
      <div className="relative w-full max-w-md" style={{ zIndex: 1 }}>
        <div className="border border-green-500/40 rounded-lg p-8 relative"
          style={{ background:'rgba(2,9,2,0.97)', boxShadow:'0 0 40px rgba(0,255,65,0.08)' }}>
          <div className="absolute top-0 left-0 w-6 h-6 border-t-2 border-l-2 border-green-400 rounded-tl-lg" />
          <div className="absolute top-0 right-0 w-6 h-6 border-t-2 border-r-2 border-green-400 rounded-tr-lg" />
          <div className="absolute bottom-0 left-0 w-6 h-6 border-b-2 border-l-2 border-green-400 rounded-bl-lg" />
          <div className="absolute bottom-0 right-0 w-6 h-6 border-b-2 border-r-2 border-green-400 rounded-br-lg" />
          <div className="text-center mb-8">
            <div className="text-green-400 text-xs mb-3 opacity-60">IWX SEC \u2014 RESTRICTED ACCESS</div>
            <h1 className="text-green-400 text-xl font-bold tracking-widest mb-1">[ SYSTEM LOCKED ]</h1>
            <p className="text-green-600 text-xs">The system has been secured. &lt;/BY IWX SEC/&gt;</p>
          </div>
          <div className="border border-green-500/30 rounded p-4 mb-6" style={{ background:'rgba(0,18,0,0.8)' }}>
            <div className="flex gap-2 mb-3">
              <div className="w-3 h-3 rounded-full bg-red-500/80" />
              <div className="w-3 h-3 rounded-full bg-yellow-500/80" />
              <div className="w-3 h-3 rounded-full bg-green-500/80" />
            </div>
            <div className="text-green-400/70 text-xs mb-2">root@sys:~$ authenticate --admin</div>
            <div className="text-green-300 text-xs">Enter admin password to proceed...</div>
          </div>
          <form onSubmit={handleSubmit} className="space-y-4">
            <div>
              <label className="text-green-500/70 text-xs mb-2 block">[ ENTER ACCESS CODE ]</label>
              <input
                type="password"
                value={password}
                onChange={e => setPassword(e.target.value)}
                disabled={loading}
                autoFocus
                placeholder="\u2022\u2022\u2022\u2022\u2022\u2022\u2022\u2022\u2022\u2022\u2022\u2022"
                className="w-full bg-transparent border border-green-500/40 rounded px-4 py-3 text-green-400 text-sm placeholder-green-900 outline-none focus:border-green-400 transition-all"
                style={{ fontFamily:'monospace' }}
              />
            </div>
            {error && (
              <div className="text-red-400 text-xs text-center py-2 border border-red-500/30 rounded animate-pulse"
                style={{ background:'rgba(160,10,10,0.15)' }}>
                {error}
              </div>
            )}
            <button type="submit" disabled={loading || !password}
              className="w-full py-3 rounded text-sm font-bold tracking-widest transition-all border"
              style={{
                background: loading ? 'rgba(0,100,30,0.3)' : 'rgba(0,200,60,0.15)',
                borderColor: loading ? 'rgba(0,255,65,0.3)' : 'rgba(0,255,65,0.6)',
                color: loading ? '#00cc44' : '#00ff41',
                cursor: (loading || !password) ? 'not-allowed' : 'pointer',
                opacity: (!password && !loading) ? 0.5 : 1,
              }}>
              {loading ? loadingText : '[ AUTHENTICATE ]'}
            </button>
          </form>
          <div className="mt-6 text-center text-green-800 text-xs">
            Unauthorized access is prohibited and will be logged.
          </div>
        </div>
      </div>
    </div>
  )
}

export default function LoginPage() {
  return (
    <Suspense>
      <LoginForm />
    </Suspense>
  )
}
