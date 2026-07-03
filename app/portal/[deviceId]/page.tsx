'use client'
import { useState, useEffect, Suspense } from 'react'
import { useParams, useSearchParams } from 'next/navigation'

function PortalForm() {
  const params = useParams()
  const searchParams = useSearchParams()
  const deviceId = params?.deviceId as string
  const clientIp = searchParams?.get('ip') || ''
  const [config, setConfig] = useState<{ title: string; message: string } | null>(null)
  const [password, setPassword] = useState('')
  const [status, setStatus] = useState<'idle' | 'loading' | 'error' | 'success'>('idle')
  const [errorMsg, setErrorMsg] = useState('')
  const [dots, setDots] = useState(0)

  useEffect(() => {
    const iv = setInterval(() => setDots(d => (d + 1) % 4), 600)
    return () => clearInterval(iv)
  }, [])

  useEffect(() => {
    if (!deviceId) return
    fetch(`/api/wifi-portal/config?deviceId=${deviceId}`)
      .then(r => r.json())
      .then(d => setConfig({ title: d.title || 'WiFi Login', message: d.message || 'Masukkan password untuk terhubung ke internet.' }))
      .catch(() => setConfig({ title: 'WiFi Login', message: 'Masukkan password untuk terhubung ke internet.' }))
  }, [deviceId])

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault()
    setStatus('loading')
    setErrorMsg('')
    try {
      const res = await fetch('/api/wifi-portal/verify', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ deviceId, password, clientIp })
      })
      if (res.ok) {
        setStatus('success')
        setTimeout(() => { window.location.href = `/portal/${deviceId}/success` }, 900)
      } else {
        const d = await res.json()
        setErrorMsg(d.error || 'Password salah')
        setStatus('error')
        setPassword('')
      }
    } catch {
      setErrorMsg('Gagal terhubung ke server')
      setStatus('error')
    }
  }

  const title = config?.title ?? 'WiFi Login'
  const message = config?.message ?? 'Masukkan password untuk terhubung ke internet.'

  return (
    <div style={{
      minHeight: '100vh', display: 'flex', alignItems: 'center', justifyContent: 'center',
      padding: 16, fontFamily: 'system-ui,-apple-system,sans-serif',
      background: 'linear-gradient(135deg,#0f0f23 0%,#1a1a3e 50%,#0f0f23 100%)'
    }}>
      <div style={{ width: '100%', maxWidth: 400 }}>
        <div style={{ textAlign: 'center', marginBottom: 32 }}>
          <div style={{
            width: 72, height: 72, borderRadius: '50%', margin: '0 auto 16px',
            background: 'linear-gradient(135deg,#6366f1,#8b5cf6)',
            display: 'flex', alignItems: 'center', justifyContent: 'center',
            boxShadow: '0 0 32px rgba(99,102,241,0.5)'
          }}>
            <svg width="36" height="36" viewBox="0 0 24 24" fill="none" stroke="white" strokeWidth="2">
              <path d="M5 12.55a11 11 0 0 1 14.08 0"/><path d="M1.42 9a16 16 0 0 1 21.16 0"/>
              <path d="M8.53 16.11a6 6 0 0 1 6.95 0"/><circle cx="12" cy="20" r="1.5" fill="white" stroke="none"/>
            </svg>
          </div>
          <h1 style={{ color: '#e2e8f0', fontSize: 22, fontWeight: 700, margin: '0 0 8px' }}>{title}</h1>
          <p style={{ color: '#94a3b8', fontSize: 14, margin: 0 }}>{message}</p>
        </div>
        <div style={{
          background: 'rgba(255,255,255,0.05)', backdropFilter: 'blur(20px)',
          border: '1px solid rgba(255,255,255,0.1)', borderRadius: 16, padding: '32px 28px',
          boxShadow: '0 20px 60px rgba(0,0,0,0.4)'
        }}>
          {status === 'success' ? (
            <div style={{ textAlign: 'center', padding: '16px 0' }}>
              <div style={{
                width: 56, height: 56, borderRadius: '50%', margin: '0 auto 16px',
                background: 'rgba(34,197,94,0.15)', border: '2px solid #22c55e',
                display: 'flex', alignItems: 'center', justifyContent: 'center'
              }}>
                <svg width="28" height="28" viewBox="0 0 24 24" fill="none" stroke="#22c55e" strokeWidth="2.5">
                  <polyline points="20 6 9 17 4 12"/>
                </svg>
              </div>
              <p style={{ color: '#22c55e', fontWeight: 600, fontSize: 16, margin: '0 0 4px' }}>Akses Diberikan!</p>
              <p style={{ color: '#64748b', fontSize: 13, margin: 0 }}>Mengalihkan...</p>
            </div>
          ) : (
            <form onSubmit={handleSubmit}>
              <label style={{ display: 'block', color: '#94a3b8', fontSize: 11, marginBottom: 8, fontWeight: 600, letterSpacing: '0.08em', textTransform: 'uppercase' }}>Password WiFi</label>
              <input type="password" value={password} onChange={e => setPassword(e.target.value)}
                disabled={status === 'loading'} placeholder="Masukkan password..." autoFocus
                style={{ width: '100%', padding: '12px 16px', marginBottom: 16,
                  background: 'rgba(255,255,255,0.06)',
                  border: `1px solid ${status === 'error' ? 'rgba(239,68,68,0.6)' : 'rgba(255,255,255,0.12)'}`,
                  borderRadius: 10, color: '#e2e8f0', fontSize: 15, outline: 'none', boxSizing: 'border-box' }} />
              {status === 'error' && (
                <div style={{ background: 'rgba(239,68,68,0.1)', border: '1px solid rgba(239,68,68,0.3)',
                  borderRadius: 8, padding: '10px 14px', marginBottom: 16,
                  color: '#fca5a5', fontSize: 13, textAlign: 'center' }}>{errorMsg}</div>
              )}
              <button type="submit" disabled={!password || status === 'loading'} style={{
                width: '100%', padding: 13,
                background: (!password || status === 'loading') ? 'rgba(99,102,241,0.3)' : 'linear-gradient(135deg,#6366f1,#8b5cf6)',
                border: 'none', borderRadius: 10, color: 'white', fontSize: 15, fontWeight: 600,
                cursor: (!password || status === 'loading') ? 'not-allowed' : 'pointer' }}>
                {status === 'loading' ? 'Memverifikasi...' : 'Hubungkan ke WiFi'}
              </button>
            </form>
          )}
        </div>
        <p style={{ textAlign: 'center', color: '#1e293b', fontSize: 12, marginTop: 20 }}>
          Dengan terhubung, Anda menyetujui syarat penggunaan jaringan ini.
        </p>
      </div>
    </div>
  )
}

export default function PortalPage() {
  return <Suspense><PortalForm /></Suspense>
}