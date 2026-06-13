/** @type {import('next').NextConfig} */
const nextConfig = {
  reactStrictMode: true,
  output: 'standalone', // Diperlukan untuk Docker self-hosting
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
