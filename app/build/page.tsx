'use client'
import { useEffect, useState, useCallback } from 'react'
import Sidebar from '@/components/Sidebar'
import { useDevice } from '@/contexts/DeviceContext'
import {
  GitBranch, RefreshCw, CheckCircle2, XCircle, Clock,
  Loader2, ExternalLink, Download, Globe, Package, Circle,
} from 'lucide-react'

interface Run {
  id: number; name: string; status: string; conclusion: string | null
  message: string; sha: string; branch: string
  updatedAt: string; createdAt: string; url: string
}
interface Deployment {
  id: string; name: string; state: string; url: string
  createdAt: string; readyAt: string | null; message: string
}
interface Artifact {
  id: number; name: string; size: number; downloadUrl: string
}
interface BuildData {
  runs: Run[]; deployments: Deployment[]; artifacts: Artifact[]; repo: string
}

function statusIcon(status: string, conclusion: string | null) {
  if (status === 'in_progress' || status === 'queued') return <Loader2 size={14} className="text-android-yellow animate-spin" />
  if (conclusion === 'success')  return <CheckCircle2 size={14} className="text-android-green" />
  if (conclusion === 'failure')  return <XCircle size={14} className="text-android-red" />
  if (conclusion === 'cancelled') return <Circle size={14} className="text-android-muted" />
  return <Clock size={14} className="text-android-muted" />
}

function conclusionBadge(status: string, conclusion: string | null) {
  if (status === 'in_progress') return 'bg-android-yellow/10 text-android-yellow border-android-yellow/30'
  if (status === 'queued')      return 'bg-android-blue/10 text-android-blue border-android-blue/30'
  if (conclusion === 'success') return 'bg-android-green/10 text-android-green border-android-green/30'
  if (conclusion === 'failure') return 'bg-android-red/10 text-android-red border-android-red/30'
  return 'bg-android-surface text-android-muted border-android-border'
}

function deployBadge(state: string) {
  if (state === 'READY')    return 'bg-android-green/10 text-android-green border-android-green/30'
  if (state === 'ERROR')    return 'bg-android-red/10 text-android-red border-android-red/30'
  if (state === 'BUILDING') return 'bg-android-yellow/10 text-android-yellow border-android-yellow/30'
  return 'bg-android-surface text-android-muted border-android-border'
}

function timeAgo(iso: string) {
  const diff = Date.now() - new Date(iso).getTime()
  const m = Math.floor(diff / 60000)
  if (m < 1)  return 'baru saja'
  if (m < 60) return `${m}m lalu`
  const h = Math.floor(m / 60)
  if (h < 24) return `${h}j lalu`
  return `${Math.floor(h / 24)}h lalu`
}

function fmtBytes(b: number) {
  if (b > 1024 * 1024) return `${(b / 1024 / 1024).toFixed(1)} MB`
  return `${(b / 1024).toFixed(0)} KB`
}

export default function BuildPage() {
  const { devices, selectedId, setSelectedId, connected } = useDevice()
  const [data, setData]       = useState<BuildData | null>(null)
  const [loading, setLoading] = useState(true)
  const [lastSync, setLastSync] = useState<string>('')

  const fetchBuild = useCallback(async () => {
    try {
      const res = await fetch('/api/build')
      const json = await res.json()
      setData(json)
      setLastSync(new Date().toLocaleTimeString())
    } catch {}
    finally { setLoading(false) }
  }, [])

  useEffect(() => {
    fetchBuild()
    const iv = setInterval(fetchBuild, 15000)
    return () => clearInterval(iv)
  }, [fetchBuild])

  const latestRun    = data?.runs?.[0]
  const isBuilding   = latestRun?.status === 'in_progress' || latestRun?.status === 'queued'

  return (
    <div className="flex min-h-screen">
      <Sidebar connected={connected} devices={devices} selectedId={selectedId} onSelect={setSelectedId} />
      <main className="flex-1 page-content overflow-y-auto">
        <div className="max-w-4xl mx-auto px-4 md:px-6 py-4 md:py-6">

          {/* Header */}
          <div className="flex items-center justify-between mb-4">
            <div>
              <h2 className="text-lg md:text-xl font-bold text-white flex items-center gap-2">
                <GitBranch size={20} className="text-android-green" /> Build Monitor
              </h2>
              <p className="text-android-muted text-xs mt-0.5">
                {data?.repo ?? 'Kztutorial99/AndroidControl'} · sync {lastSync || '…'}
              </p>
            </div>
            <button onClick={fetchBuild} disabled={loading}
              className="flex items-center gap-1.5 px-3 py-1.5 bg-android-surface border border-android-border rounded-lg text-xs text-android-muted hover:text-white transition-colors disabled:opacity-40">
              <RefreshCw size={12} className={loading ? 'animate-spin' : ''} />
              Refresh
            </button>
          </div>

          {loading && !data && (
            <div className="flex items-center justify-center py-16 text-android-muted text-sm gap-2">
              <Loader2 size={16} className="animate-spin" /> Memuat data build…
            </div>
          )}

          {data && (
            <>
              {/* Summary bar */}
              <div className={`mb-4 px-4 py-3 rounded-xl border flex items-center gap-3 ${
                isBuilding ? 'bg-android-yellow/10 border-android-yellow/30' :
                latestRun?.conclusion === 'success' ? 'bg-android-green/10 border-android-green/30' :
                latestRun?.conclusion === 'failure' ? 'bg-android-red/10 border-android-red/30' :
                'bg-android-surface border-android-border'
              }`}>
                {latestRun && statusIcon(latestRun.status, latestRun.conclusion)}
                <div className="flex-1 min-w-0">
                  <p className={`text-sm font-semibold ${
                    isBuilding ? 'text-android-yellow' :
                    latestRun?.conclusion === 'success' ? 'text-android-green' :
                    latestRun?.conclusion === 'failure' ? 'text-android-red' : 'text-android-muted'
                  }`}>
                    {isBuilding ? 'Build sedang berjalan…' :
                     latestRun?.conclusion === 'success' ? 'Build berhasil ✅' :
                     latestRun?.conclusion === 'failure' ? 'Build gagal ❌' : 'Menunggu…'}
                  </p>
                  {latestRun && (
                    <p className="text-xs text-android-muted truncate">{latestRun.message}</p>
                  )}
                </div>
                {latestRun && (
                  <a href={latestRun.url} target="_blank" rel="noopener noreferrer"
                    className="shrink-0 p-1.5 rounded-lg hover:bg-white/10 transition-colors text-android-muted hover:text-white">
                    <ExternalLink size={13} />
                  </a>
                )}
              </div>

              {/* Artifacts */}
              {data.artifacts.length > 0 && (
                <div className="mb-4 bg-android-surface border border-android-border rounded-xl p-4">
                  <h3 className="text-xs font-semibold text-android-muted uppercase tracking-wider mb-3 flex items-center gap-2">
                    <Package size={13} /> APK Artifacts
                  </h3>
                  <div className="space-y-2">
                    {data.artifacts.map(a => (
                      <div key={a.id} className="flex items-center gap-3 py-2 border-b border-android-border/50 last:border-0">
                        <div className="flex-1 min-w-0">
                          <p className="text-sm text-android-text font-medium truncate">{a.name}</p>
                          <p className="text-xs text-android-muted">{fmtBytes(a.size)}</p>
                        </div>
                        <a href={a.downloadUrl} target="_blank" rel="noopener noreferrer"
                          className="flex items-center gap-1.5 px-3 py-1.5 bg-android-green/10 border border-android-green/30 text-android-green rounded-lg text-xs font-semibold hover:bg-android-green/20 transition-colors">
                          <Download size={12} /> Download
                        </a>
                      </div>
                    ))}
                  </div>
                </div>
              )}

              {/* Grid: GitHub Runs + Vercel Deploys */}
              <div className="grid grid-cols-1 md:grid-cols-2 gap-4">

                {/* GitHub Actions */}
                <div className="bg-android-surface border border-android-border rounded-xl overflow-hidden">
                  <div className="px-4 py-3 border-b border-android-border flex items-center gap-2">
                    <GitBranch size={14} className="text-android-muted" />
                    <h3 className="text-xs font-semibold text-android-muted uppercase tracking-wider">GitHub Actions</h3>
                    <span className="ml-auto text-xs text-android-muted">{data.runs.length} runs</span>
                  </div>
                  <div className="divide-y divide-android-border/50">
                    {data.runs.map(run => (
                      <a key={run.id} href={run.url} target="_blank" rel="noopener noreferrer"
                        className="flex items-start gap-3 px-4 py-3 hover:bg-white/5 transition-colors group">
                        <div className="mt-0.5 shrink-0">{statusIcon(run.status, run.conclusion)}</div>
                        <div className="flex-1 min-w-0">
                          <div className="flex items-center gap-2 mb-0.5">
                            <span className={`text-[10px] font-semibold px-1.5 py-0.5 rounded border ${conclusionBadge(run.status, run.conclusion)}`}>
                              {run.status === 'in_progress' ? 'running' : run.conclusion ?? run.status}
                            </span>
                            <span className="text-[10px] text-android-muted font-mono">{run.sha}</span>
                          </div>
                          <p className="text-xs text-android-text truncate font-medium">{run.message}</p>
                          <p className="text-[10px] text-android-muted mt-0.5">{run.branch} · {timeAgo(run.updatedAt)}</p>
                        </div>
                        <ExternalLink size={11} className="text-android-muted shrink-0 mt-1 opacity-0 group-hover:opacity-100 transition-opacity" />
                      </a>
                    ))}
                  </div>
                </div>

                {/* Vercel Deploys */}
                <div className="bg-android-surface border border-android-border rounded-xl overflow-hidden">
                  <div className="px-4 py-3 border-b border-android-border flex items-center gap-2">
                    <Globe size={14} className="text-android-muted" />
                    <h3 className="text-xs font-semibold text-android-muted uppercase tracking-wider">Vercel Deploys</h3>
                    <span className="ml-auto text-xs text-android-muted">{data.deployments.length} deploys</span>
                  </div>
                  <div className="divide-y divide-android-border/50">
                    {data.deployments.map(dep => (
                      <a key={dep.id} href={dep.url} target="_blank" rel="noopener noreferrer"
                        className="flex items-start gap-3 px-4 py-3 hover:bg-white/5 transition-colors group">
                        <div className="mt-0.5 shrink-0">
                          {dep.state === 'READY'    ? <CheckCircle2 size={14} className="text-android-green" /> :
                           dep.state === 'ERROR'    ? <XCircle size={14} className="text-android-red" /> :
                           dep.state === 'BUILDING' ? <Loader2 size={14} className="text-android-yellow animate-spin" /> :
                           <Clock size={14} className="text-android-muted" />}
                        </div>
                        <div className="flex-1 min-w-0">
                          <div className="flex items-center gap-2 mb-0.5">
                            <span className={`text-[10px] font-semibold px-1.5 py-0.5 rounded border ${deployBadge(dep.state)}`}>
                              {dep.state}
                            </span>
                          </div>
                          {dep.message && (
                            <p className="text-xs text-android-text truncate font-medium">{dep.message}</p>
                          )}
                          <p className="text-[10px] text-android-muted mt-0.5 truncate">{dep.url.replace('https://', '')} · {timeAgo(dep.createdAt)}</p>
                        </div>
                        <ExternalLink size={11} className="text-android-muted shrink-0 mt-1 opacity-0 group-hover:opacity-100 transition-opacity" />
                      </a>
                    ))}
                  </div>
                </div>
              </div>
            </>
          )}
        </div>
      </main>
    </div>
  )
}
