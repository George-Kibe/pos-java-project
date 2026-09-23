/**
 * Permission checks, shared by server and client. Permissions are the unit of access - never role
 * names - exactly as the services check them.
 */
export function hasAny(held: readonly string[], required: readonly string[]): boolean {
  return required.length === 0 || required.some((permission) => held.includes(permission));
}
