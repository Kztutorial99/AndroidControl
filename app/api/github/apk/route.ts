import { NextRequest, NextResponse } from 'next/server'
import JSZip from 'jszip'

export const dynamic = 'force-dynamic'
export const maxDuration = 60

const OWNER = 'Kztutorial99'
const REPO  = 'AndroidControl'
const TOKEN = process.env.GITHUB_TOKEN ?? ''

async function getLatestArtifact(mode: 'debug' | 'release') {
  const prefix = mode === 'release' ? 'AndroidConnector-release' : 'AndroidConnector-debug'
  // Ambil 10 run terbaru yang success
  const runsRes = await fetch(
    `https://api.github.com/repos/${OWNER}/${REPO}/actions/runs?status=success&per_page=10`,
    { headers: { Authorization: `Bearer ${TOKEN}`, Accept: 'application/vnd.github+json' } }
  )
  const runs = await runsRes.json()
  const latestRun = (runs.workflow_runs ?? [])[0]
  if (!latestRun) return null

  const artRes = await fetch(
    `https://api.github.com/repos/${OWNER}/${REPO}/actions/runs/${latestRun.id}/artifacts`,
    { headers: { Authorization: `Bearer ${TOKEN}`, Accept: 'application/vnd.github+json' } }
  )
  const artData = await artRes.json()
  const artifact = (artData.artifacts ?? []).find(
    (a: { name: string; expired: boolean; size_in_bytes: number; created_at: string }) =>
      a.name.startsWith(prefix) && !a.expired
  )
  return artifact ? { ...artifact, runId: latestRun.id, commitSha: latestRun.head_sha } : null
}

// GET /api/github/apk?mode=debug|release&action=info|download
export async function GET(req: NextRequest) {
  const mode    = (req.nextUrl.searchParams.get('mode') ?? 'release') as 'debug' | 'release'
  const action  = req.nextUrl.searchParams.get('action') ?? 'download'

  if (!TOKEN) return NextResponse.json({ error: 'GITHUB_TOKEN not set' }, { status: 500 })

  const artifact = await getLatestArtifact(mode)
  if (!artifact) {
    return NextResponse.json({ error: `Tidak ada artifact ${mode} yang tersedia` }, { status: 404 })
  }

  // Info only
  if (action === 'info') {
    return NextResponse.json({
      name: artifact.name,
      size: artifact.size_in_bytes,
      sizeMb: (artifact.size_in_bytes / 1024 / 1024).toFixed(1),
      createdAt: artifact.created_at,
      commitSha: artifact.commitSha?.slice(0, 7),
      mode,
    })
  }

  // Download — proxy APK dari artifact ZIP
  try {
    const zipRes = await fetch(artifact.archive_download_url, {
      headers: { Authorization: `Bearer ${TOKEN}`, Accept: 'application/vnd.github+json' },
      redirect: 'follow',
    })
    if (!zipRes.ok) {
      return NextResponse.json({ error: 'Gagal download artifact dari GitHub' }, { status: 502 })
    }

    const zipBuffer = await zipRes.arrayBuffer()
    const zip = await JSZip.loadAsync(zipBuffer)

    // Cari file .apk di dalam ZIP
    const apkEntry = Object.values(zip.files).find(f => !f.dir && f.name.endsWith('.apk'))
    if (!apkEntry) {
      return NextResponse.json({ error: 'APK tidak ditemukan di dalam artifact ZIP' }, { status: 404 })
    }

    const apkBuffer = await apkEntry.async('arraybuffer')
    const filename  = `AndroidConnector-${mode}.apk`

    return new NextResponse(apkBuffer, {
      headers: {
        'Content-Type': 'application/vnd.android.package-archive',
        'Content-Disposition': `attachment; filename="${filename}"`,
        'Content-Length': String(apkBuffer.byteLength),
        'Cache-Control': 'public, max-age=1800',
        'X-APK-Mode': mode,
        'X-Commit': artifact.commitSha?.slice(0, 7) ?? '',
      },
    })
  } catch (err) {
    console.error('APK proxy error:', err)
    return NextResponse.json({ error: 'Internal error saat memproses APK' }, { status: 500 })
  }
}
