"use client";

import { useQuery } from "@tanstack/react-query";
import { useEffect, useState } from "react";
import { z } from "zod";

import { CheckField } from "@/components/admin/form-parts";
import { api } from "@/lib/api/client";
import { type CostCheck, CostCheckSchema } from "@/lib/api/catalog-schemas";
import { money } from "@/lib/lane/decimal";

/** The request, debounced as a string so a new array each render does not re-ask catalog. */
const CostRequest = z.object({ branchId: z.string(), includesTax: z.boolean(), lines: z.array(z.tuple([z.string(), z.string()])) });

/** A fraction as a whole percentage: 0.15 is "15%", 0.052 is "5.2%". */
export function percent(fraction: number): string {
  return `${Number((fraction * 100).toFixed(1))}%`;
}

/**
 * The switch for costs keyed in as invoiced. The business reclaims VAT, so costs are kept without
 * it; with this on, catalog takes each product's VAT out of what was typed.
 */
export function VatSwitch({ id, checked, onChange }: { id: string; checked: boolean; onChange: (checked: boolean) => void }) {
  return <CheckField id={id} label="Costs include VAT" checked={checked} onChange={onChange} hint="As printed on the supplier's invoice. Stock is valued without VAT, which is reclaimed." />;
}

/**
 * Costs as they are typed, judged against the branch's prices: each product's cost without VAT, the
 * margin it leaves, and a price when that is short of the target. Nothing is saved by asking.
 */
export function useCostChecks(branchId: string, includesTax: boolean, lines: { productId: string; unitCost: string }[]): Map<string, CostCheck> {
  const priced = lines.filter((line) => line.unitCost.trim() !== "" && Number(line.unitCost) >= 0);
  const request = JSON.stringify({ branchId, includesTax, lines: priced.map((l) => [l.productId, l.unitCost.trim()]) });
  const [debounced, setDebounced] = useState(request);
  useEffect(() => {
    const timer = setTimeout(() => setDebounced(request), 350);
    return () => clearTimeout(timer);
  }, [request]);
  const parsed = CostRequest.parse(JSON.parse(debounced));
  const checks = useQuery({
    queryKey: ["cost-check", debounced],
    queryFn: () =>
      api("pricing/cost-check", z.array(CostCheckSchema), {
        method: "POST",
        json: {
          branchId: parsed.branchId || undefined,
          costIncludesTax: parsed.includesTax,
          lines: parsed.lines.map(([productId, unitCost]) => ({ productId, unitCost })),
        },
      }),
    enabled: parsed.lines.length > 0,
    retry: false,
  });
  return new Map((checks.data ?? []).map((check) => [check.productId, check]));
}

/** One line's verdict, under its cost. Only a shortfall is loud; an ordinary margin is quiet. */
export function CostVerdict({ check }: { check: CostCheck | undefined }) {
  if (!check) return null;
  const without = check.taxRate > 0 && check.netUnitCost !== check.unitCost ? `${money(check.netUnitCost)} without VAT · ` : "";
  const margin = check.margin === null ? "no price set" : `margin ${percent(check.margin)}`;
  const target = check.targetMargin === null ? "" : ` (target ${percent(check.targetMargin)})`;
  const basis = check.priceIncludesTax ? "with VAT" : "without VAT";
  if (check.status === "BELOW_COST" || check.status === "BELOW_TARGET") {
    return (
      <p role="status" className="text-sm font-medium text-destructive" data-testid="cost-verdict">
        {without}
        {check.status === "BELOW_COST" ? `Sells below cost at ${money(check.price)}` : `${margin}${target} at ${money(check.price)}`}. Suggested price {money(check.suggestedPrice ?? 0)} {basis}; a manager is asked once the stock is received.
      </p>
    );
  }
  return (
    <p className="text-sm text-muted-foreground" data-testid="cost-verdict">
      {without}
      {margin}
      {target} at {money(check.price)}
    </p>
  );
}
