/**
 * The browser's address and agent, passed on with every credential call - login, refresh,
 * registration. auth-service records them against the session, and the gateway rate-limits
 * credential endpoints per address; without them every user would arrive from the BFF's one
 * address and share a single small bucket.
 *
 * Next fills in x-forwarded-for from the socket when a request arrives without one.
 */
export function clientHeaders(request: Request): Record<string, string> {
  const headers: Record<string, string> = {};
  const forwarded = request.headers.get("x-forwarded-for");
  if (forwarded) {
    headers["X-Forwarded-For"] = forwarded;
  }
  const agent = request.headers.get("user-agent");
  if (agent) {
    headers["User-Agent"] = agent;
  }
  return headers;
}
