import { NextResponse } from 'next/server'

export const dynamic = 'force-dynamic'

const OWNER = 'Kztutorial99'
const REPO  = 'AndroidControl'
const TOKEN = process.env.GITHUB_TOKEN ?? ''

export async function GET() {
  if (!TOKEN) return NextResponse.json({ error: 'No token' }, { status: 500 })
  try {
    const res = await fetch(
      `https://api.github.com/repos/${OWNER}/${REPO}/actions/runs?per_page=5`,
      { headers: { Authorization: `Bearer ${TOKEN}`, Accept: 'application/vnd.github+json' },
        next: { revalidate: 0 } }
    )
    const data = await res.json()
    const runs: Array<{
      id: number; status: string; conclusion: string | null;
      head_sha: string; created_at: string; updated_at: string;
      name: string; html_url: string;
    }> = data.workflow_runs ?? []

    // Ambil run terbaru & run yang sukses terbaru
    const latest   = runs[0] ?? null
    const lastOk   = runs.find(r => r.conclusion === 'success') ?? null

    return NextResponse.json({
      latest: latest ? {
        id:         latest.id,
        status:     latest.status,
        conclusion: latest.conclusion,
        commitSha:  latest.head_sha.slice(0, 7),
        fullSha:    latest.head_sha,
        createdAt:  latest.created_at,
        updatedAt:  latest.updated_at,
        url:        latest.html_url,
      } : null,
      lastSuccess: lastOk ? {
        id:         lastOk.id,
        commitSha:  lastOk.head_sha.slice(0, 7),
        fullSha:    lastOk.head_sha,
        createdAt:  lastOk.created_at,
        updatedAt:  lastOk.updated_at,
        url:        lastOk.html_url,
      } : null,
    })
  } catch (e) {
    console.error('builds route error:', e)
    return NextResponse.json({ error: 'Gagal fetch build info' }, { status: 500 })
  }
}
