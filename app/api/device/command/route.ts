import { NextRequest, NextResponse } from 'next/server'
import { store, enqueuCommand } from '@/lib/store'

export async function POST(req: NextRequest) {
  try {
    const body = await req.json()
    const { command } = body

    if (!command || typeof command !== 'string') {
      return NextResponse.json({ error: 'command is required' }, { status: 400 })
    }

    const pending = enqueuCommand(command.trim())

    return NextResponse.json({
      ok: true,
      commandId: pending.id,
      command: pending.command,
      queued: true,
    })
  } catch {
    return NextResponse.json({ error: 'Bad request' }, { status: 400 })
  }
}

export async function GET() {
  return NextResponse.json({
    pending: store.pendingCommands,
    count: store.pendingCommands.length,
  })
}
