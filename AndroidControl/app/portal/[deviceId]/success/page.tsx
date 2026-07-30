export default function PortalSuccess() {
  return (
    <div style={{
      minHeight: '100vh', display: 'flex', alignItems: 'center', justifyContent: 'center',
      fontFamily: 'system-ui,-apple-system,sans-serif',
      background: 'linear-gradient(135deg,#0f0f23 0%,#1a1a3e 50%,#0f0f23 100%)'
    }}>
      <div style={{ textAlign: 'center', padding: 32 }}>
        <div style={{
          width: 80, height: 80, borderRadius: '50%', margin: '0 auto 24px',
          background: 'rgba(34,197,94,0.15)', border: '3px solid #22c55e',
          display: 'flex', alignItems: 'center', justifyContent: 'center'
        }}>
          <svg width="40" height="40" viewBox="0 0 24 24" fill="none" stroke="#22c55e" strokeWidth="2.5">
            <polyline points="20 6 9 17 4 12"/>
          </svg>
        </div>
        <h1 style={{ color: '#22c55e', fontSize: 24, fontWeight: 700, margin: '0 0 12px' }}>Terhubung!</h1>
        <p style={{ color: '#94a3b8', fontSize: 15, margin: '0 0 8px' }}>Akses internet telah diberikan.</p>
        <p style={{ color: '#64748b', fontSize: 13, margin: 0 }}>Anda sekarang dapat menggunakan WiFi secara bebas.</p>
      </div>
    </div>
  )
}