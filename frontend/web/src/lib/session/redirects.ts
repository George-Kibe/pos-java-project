/**
 * Where to go after signing in. Only a path on this site: `next=//evil.example` or an absolute URL
 * would turn the login page into an open redirect.
 */
export function safeNext(next: string | null | undefined): string {
  if (!next || !next.startsWith("/") || next.startsWith("//") || next.startsWith("/\\")) {
    return "/";
  }
  return next;
}

export const PUBLIC_PATHS = ["/login", "/register", "/forgot-password", "/reset-password"] as const;
export const CHANGE_PASSWORD_PATH = "/account/password";
/** Pages open with or without a session: registering a device happens before anyone signs in there. */
export const ANY_SESSION_PATHS = ["/register-device"] as const;

export function isAnySessionPath(pathname: string): boolean {
  return ANY_SESSION_PATHS.some((path) => pathname === path || pathname.startsWith(`${path}/`));
}

export function isPublicPath(pathname: string): boolean {
  return PUBLIC_PATHS.some((path) => pathname === path || pathname.startsWith(`${path}/`));
}
