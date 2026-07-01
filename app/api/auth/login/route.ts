import { NextRequest, NextResponse } from 'next/server'
import { createHash } from 'crypto'

const PASS_HASH = 'dfa3cf6eb60e9ef0815963a8160181432fe1ba87e44b10f77f4d4a6248c31f2a'

export async function POST(req: NextRequest) {
  try {
    const { password } = await req.json()
    if (!password || typeof password !== 'string') {
      return NextResponse.json({ error: 'Password required' }, { status: 400 })
    }
    const hash = createHash('sha256').update(password).digest('hex')
    if (hash !== PASS_HASH) {
      return NextResponse.json({ error: 'Invalid password' }, { status: 401 })
    }
    const res = NextResponse.json({ ok: true })
    res.cookies.set('iwx_auth', PASS_HASH, {
      httpOnly: true,
      secure: process.env.NODE_ENV === 'production',
      sameSite: 'strict',
      maxAge: 60 * 60 * 24 * 7,
      path: '/',
    })
    return res
  } catch {
    return NextResponse.json({ error: 'Server error' }, { status: 500 })
  }
}
