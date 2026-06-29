import { NextResponse } from 'next/server'

const GITHUB_TOKEN  = process.env.GITHUB_TOKEN  ?? ''
const VERCEL_TOKEN  = process.env.VERCEL_TOKEN  ?? ''
const REPO          = 'Kztutorial99/AndroidControl'
const GH_HEADERS    = {
  Authorization:       `Bearer ${GITHUB_TOKEN}`,
  Accept:              'application/vnd.github+json',
  'X-GitHub-Api-Version': '2022-11-28',
}

export async function GET() {
  try {
    const [runsRes, deploysRes] = await Promise.all([
      fetch(`https://api.github.com/repos/${REPO}/actions/runs?per_page=10`, { headers: GH_HEADERS }),
      fetch('https://api.vercel.com/v6/deployments?limit=8', {
        headers: { Authorization: `Bearer ${VERCEL_TOKEN}` },
      }),
    ])

    const runsData    = await runsRes.json()
    const deploysData = await deploysRes.json()

    const runs = (runsData.workflow_runs ?? []).map((r: {
      id: number; name: string; status: string; conclusion: string | null;
      head_commit: { message: string; id: string } | null;
      updated_at: string; created_at: string; html_url: string;
      head_branch: string;
    }) => ({
      id:          r.id,
      name:        r.name,
      status:      r.status,
      conclusion:  r.conclusion,
      message:     r.head_commit?.message?.split('\n')[0]?.slice(0, 72) ?? '',
      sha:         r.head_commit?.id?.slice(0, 7) ?? '',
      branch:      r.head_branch,
      updatedAt:   r.updated_at,
      createdAt:   r.created_at,
      url:         r.html_url,
    }))

    const deployments = (deploysData.deployments ?? []).map((d: {
      uid: string; name: string; state: string; url: string;
      createdAt: number; readyAt?: number; meta?: { githubCommitMessage?: string };
    }) => ({
      id:        d.uid,
      name:      d.name,
      state:     d.state,
      url:       `https://${d.url}`,
      createdAt: new Date(d.createdAt).toISOString(),
      readyAt:   d.readyAt ? new Date(d.readyAt).toISOString() : null,
      message:   (d.meta?.githubCommitMessage ?? '').split('\n')[0]?.slice(0, 72),
    }))

    // Ambil artifacts dari run terbaru yang sukses
    const successRun = runs.find((r: { conclusion: string }) => r.conclusion === 'success')
    let artifacts: { id: number; name: string; size: number; downloadUrl: string }[] = []
    if (successRun) {
      const artRes  = await fetch(
        `https://api.github.com/repos/${REPO}/actions/runs/${successRun.id}/artifacts`,
        { headers: GH_HEADERS }
      )
      const artData = await artRes.json()
      artifacts = (artData.artifacts ?? []).map((a: {
        id: number; name: string; size_in_bytes: number; archive_download_url: string;
      }) => ({
        id:          a.id,
        name:        a.name,
        size:        a.size_in_bytes,
        downloadUrl: a.archive_download_url,
      }))
    }

    return NextResponse.json({ runs, deployments, artifacts, repo: REPO })
  } catch (e) {
    return NextResponse.json({ error: String(e) }, { status: 500 })
  }
}
