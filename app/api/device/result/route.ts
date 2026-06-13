import { NextRequest, NextResponse } from 'next/server'
import { store, addCommandToHistory } from '@/lib/store'
import { v4 as uuidv4 } from 'uuid'

export async function POST(req: NextRequest) {
  try {
    const body = await req.json()
    const { token, commandId, command, result, exitCode, type, data } = body

    if (token !== store.token) {
      return NextResponse.json({ error: 'Invalid token' }, { status: 401 })
    }

    if (type === 'file_listing') {
      store.fileListing = {
        path: data?.path ?? '/',
        entries: data?.entries ?? [],
      }
      return NextResponse.json({ ok: true })
    }

    addCommandToHistory({
      id: commandId ?? uuidv4(),
      command: command ?? '',
      result: result ?? '',
      timestamp: new Date().toISOString(),
      exitCode: exitCode ?? 0,
    })

    return NextResponse.json({ ok: true })
  } catch {
    return NextResponse.json({ error: 'Bad request' }, { status: 400 })
  }
}

export async function GET() {
  return NextResponse.json({
    history: store.commandHistory,
  })
}
