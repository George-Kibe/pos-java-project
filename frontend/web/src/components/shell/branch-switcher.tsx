"use client";

import { Store } from "lucide-react";
import { useRouter } from "next/navigation";
import { useTransition } from "react";
import { toast } from "sonner";

import { useSession } from "@/components/session-provider";
import { Button } from "@/components/ui/button";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuGroup,
  DropdownMenuLabel,
  DropdownMenuRadioGroup,
  DropdownMenuRadioItem,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { bff } from "@/lib/api/client";
import { ApiError } from "@/lib/api/errors";
import { BranchSummarySchema } from "@/lib/api/schemas";

/** Shows the branch being worked in; a user assigned to several can switch. */
export function BranchSwitcher() {
  const { branches, activeBranch } = useSession();
  const router = useRouter();
  const [pending, startTransition] = useTransition();

  if (!activeBranch) {
    return <span className="text-sm text-muted-foreground">No branch assigned</span>;
  }
  if (branches.length < 2) {
    return (
      <span className="inline-flex items-center gap-2 text-sm font-medium" data-testid="branch-name">
        <Store className="size-4" aria-hidden /> {activeBranch.name}
      </span>
    );
  }

  function choose(branchId: string) {
    if (branchId === activeBranch?.id) return;
    startTransition(async () => {
      try {
        const branch = await bff("/api/session/branch", BranchSummarySchema, {
          json: { branchId },
        });
        toast.success(`Now working in ${branch.name}`);
        router.refresh();
      } catch (error) {
        toast.error(error instanceof ApiError ? error.message : "Could not switch branch");
      }
    });
  }

  return (
    <DropdownMenu>
      <DropdownMenuTrigger
        render={<Button variant="outline" disabled={pending} data-testid="branch-switcher" />}
      >
        <Store aria-hidden /> {activeBranch.name}
      </DropdownMenuTrigger>
      <DropdownMenuContent align="end" className="min-w-56">
        <DropdownMenuGroup>
          <DropdownMenuLabel>Work in branch</DropdownMenuLabel>
          <DropdownMenuRadioGroup value={activeBranch.id} onValueChange={choose}>
            {branches.map((branch) => (
              <DropdownMenuRadioItem key={branch.id} value={branch.id} className="min-h-11">
                {branch.name}
                <span className="ml-auto text-xs text-muted-foreground">{branch.code}</span>
              </DropdownMenuRadioItem>
            ))}
          </DropdownMenuRadioGroup>
        </DropdownMenuGroup>
      </DropdownMenuContent>
    </DropdownMenu>
  );
}
