/** @type {import('next').NextConfig} */
const nextConfig = {
  output: 'standalone',
  poweredByHeader: false,
  reactStrictMode: true,
  // When reached via Tailscale ingress, /api and /actuator are split off to the
  // backend before they ever reach this Next.js server — these rewrites never run.
  // When reached directly via localhost:3000 (port-forward), the browser hits
  // Next.js for everything, so we proxy /api and /actuator to the backend Service
  // via cluster DNS from inside the frontend pod.
  async rewrites() {
    const backend = process.env.BACKEND_URL || 'http://storage-console-backend.homelab.svc.cluster.local:8080';
    return [
      { source: '/api/:path*', destination: `${backend}/api/:path*` },
      { source: '/actuator/:path*', destination: `${backend}/actuator/:path*` },
    ];
  },
};

module.exports = nextConfig;
