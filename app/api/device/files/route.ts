import { NextRequest, NextResponse } from 'next/server'
import { store, enqueuCommand } from '@/lib/store'

export async function GET() {
  return NextResponse.json({
    listing: store.fileListing,
  })
}

export async function POST(req: NextRequest) {
  try {
    const body = await req.json()
    const { path } = body
    const safePath = path?.replace(/[`$]/g, '') ?? '/storage/emulated/0'

    const cmd =
      `ls -la "${safePath}" 2>&1 | awk 'NR>1{print}' && echo "___PATH:${safePath}"`

    const pending = enqueuCommand(cmd)

    return NextResponse.json({ ok: true, commandId: pending.id, path: safePath })
  } catch {
    return NextResponse.json({ error: 'Bad request' }, { status: 400 })
  }
}
