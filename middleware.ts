import { NextResponse } from 'next/server'
import type { NextRequest } from 'next/server'

const AUTH_COOKIE = 'iwx_auth'
const PASS_HASH = 'dfa3cf6eb60e9ef0815963a8160181432fe1ba87e44b10f77f4d4a6248c31f2a'

export function middleware(request: NextRequest) {
  const { pathname } = request.nextUrl

  // Skip auth: login page, auth API, device API (used by Android app), static assets
  if (
    pathname.startsWith('/login') ||
    pathname.startsWith('/api/auth') ||
    pathname.startsWith('/api/device') ||
    pathname.startsWith('/api/devices') ||
    pathname.startsWith('/_next') ||
    pathname === '/icon.svg' ||
    pathname === '/manifest.json' ||
    pathname === '/favicon.ico'
  ) {
    return NextResponse.next()
  }

  const token = request.cookies.get(AUTH_COOKIE)?.value
  if (token !== PASS_HASH) {
    const loginUrl = new URL('/login', request.url)
    loginUrl.searchParams.set('from', pathname)
    return NextResponse.redirect(loginUrl)
  }

  return NextResponse.next()
}

export const config = {
  matcher: ['/((?!_next/static|_next/image).*)'],
}
