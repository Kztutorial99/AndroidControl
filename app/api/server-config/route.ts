import { NextResponse } from 'next/server'

export const dynamic = 'force-dynamic'

const OWNER = 'Kztutorial99'
const REPO  = 'AndroidControl'
const FILE  = 'android-config.json'
const TOKEN = process.env.GITHUB_TOKEN ?? ''

export async function GET() {
  try {
    const res = await fetch(
      `https://api.github.com/repos/${OWNER}/${REPO}/contents/${FILE}`,
      { headers: { Authorization: `Bearer ${TOKEN}`, Accept: 'application/vnd.github.v3+json' },
        next: { revalidate: 0 } }
    )
    const data = await res.json()
    const config = JSON.parse(Buffer.from(data.content, 'base64').toString('utf8'))
    return NextResponse.json({ serverUrl: config.serverUrl, version: config.version, sha: data.sha })
  } catch {
    return NextResponse.json({ error: 'Gagal fetch config' }, { status: 500 })
  }
}

export async function PUT(req: Request) {
  if (!TOKEN) return NextResponse.json({ error: 'No GitHub token' }, { status: 500 })
  try {
    const { serverUrl } = await req.json()
    if (!serverUrl || !serverUrl.startsWith('http')) {
      return NextResponse.json({ error: 'URL tidak valid — harus diawali http/https' }, { status: 400 })
    }

    const getRes  = await fetch(
      `https://api.github.com/repos/${OWNER}/${REPO}/contents/${FILE}`,
      { headers: { Authorization: `Bearer ${TOKEN}`, Accept: 'application/vnd.github.v3+json' } }
    )
    const getData = await getRes.json()
    const current = JSON.parse(Buffer.from(getData.content, 'base64').toString('utf8'))

    const newConfig = {
      serverUrl: serverUrl.trim().replace(/\/$/, ''),
      version:   (current.version ?? 1) + 1,
    }
    const newB64 = Buffer.from(JSON.stringify(newConfig, null, 2)).toString('base64')

    const putRes = await fetch(
      `https://api.github.com/repos/${OWNER}/${REPO}/contents/${FILE}`,
      {
        method:  'PUT',
        headers: {
          Authorization:  `Bearer ${TOKEN}`,
          Accept:         'application/vnd.github.v3+json',
          'Content-Type': 'application/json',
        },
        body: JSON.stringify({
          message: `config: update serverUrl → ${newConfig.serverUrl}`,
          content: newB64,
          sha:     getData.sha,
        }),
      }
    )
    const putData = await putRes.json()
    if (!putRes.ok) return NextResponse.json({ error: putData.message ?? 'Push gagal' }, { status: putRes.status })

    return NextResponse.json({
      ok:        true,
      serverUrl: newConfig.serverUrl,
      version:   newConfig.version,
      commit:    (putData.commit?.sha ?? '').substring(0, 7),
    })
  } catch (e) {
    return NextResponse.json({ error: String(e) }, { status: 500 })
  }
}
