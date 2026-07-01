/** @type {import('next').NextConfig} */
const nextConfig = {
  reactStrictMode: true,
  // output: 'standalone', // Aktifkan untuk Docker; Netlify pakai plugin-nya sendiri
  // Allow images from any source
  images: {
    unoptimized: true,
  },
  // Headers for security + caching
  async headers() {
    return [
      {
        source: '/api/:path*',
        headers: [
          { key: 'Cache-Control', value: 'no-store, no-cache, must-revalidate' },
        ],
      },
    ]
  },
}

module.exports = nextConfig
