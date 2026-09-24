import { gatewayFetch } from "@/lib/api/gateway";

/**
 * Can the lane reach the services? The browser's own online flag only knows about the network
 * cable; this answers whether a sale sent now would arrive. 204 when the gateway is up.
 */
export async function GET() {
  try {
    const health = await gatewayFetch("/actuator/health", { signal: AbortSignal.timeout(3_000) });
    return new Response(null, { status: health.ok ? 204 : 503, headers: { "Cache-Control": "no-store" } });
  } catch {
    return new Response(null, { status: 503, headers: { "Cache-Control": "no-store" } });
  }
}
