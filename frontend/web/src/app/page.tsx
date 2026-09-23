import { redirect } from "next/navigation";

import { requireUser } from "@/lib/auth/dal";
import { homeFor } from "@/lib/nav";

/** No page of its own: each user starts where their work is. */
export default async function Home() {
  const user = await requireUser();
  redirect(homeFor(user.permissions));
}
