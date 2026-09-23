import type { Metadata } from "next";

import { Forbidden } from "@/components/forbidden";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { ApiError } from "@/lib/api/errors";
import { gatewayJson } from "@/lib/api/gateway";
import { type Dashboard, DashboardSchema } from "@/lib/api/schemas";
import { can, getAccessToken, getActiveBranch, requireUser } from "@/lib/auth/dal";

export const metadata: Metadata = { title: "Dashboard" };

const money = new Intl.NumberFormat("en-KE", { style: "currency", currency: "KES" });

export default async function DashboardPage() {
  const user = await requireUser();
  if (!can(user, "report:view", "report:view:branch")) {
    return <Forbidden what="the reports" />;
  }
  const branch = await getActiveBranch(user);
  if (!branch) {
    return (
      <Alert className="max-w-xl">
        <AlertTitle>No branch assigned</AlertTitle>
        <AlertDescription>The dashboard shows one branch at a time. Ask an administrator to assign you one.</AlertDescription>
      </Alert>
    );
  }

  let dashboard: Dashboard | null = null;
  let failure: string | null = null;
  try {
    dashboard = await gatewayJson(`/api/v1/dashboards?branchId=${branch.id}`, DashboardSchema, {
      accessToken: (await getAccessToken()) ?? undefined,
    });
  } catch (error) {
    failure = error instanceof ApiError ? error.message : "The dashboard could not be loaded.";
  }

  return (
    <div className="grid gap-6">
      <div>
        <h1 className="text-3xl font-semibold tracking-tight">Dashboard</h1>
        <p className="text-muted-foreground">
          {branch.name}
          {dashboard ? ` · ${dashboard.businessDate}` : ""}
        </p>
      </div>
      {failure ? (
        <Alert variant="destructive" className="max-w-xl">
          <AlertTitle>Reports unavailable</AlertTitle>
          <AlertDescription>{failure}</AlertDescription>
        </Alert>
      ) : dashboard ? (
        <DashboardFigures dashboard={dashboard} />
      ) : null}
    </div>
  );
}

function DashboardFigures({ dashboard }: { dashboard: Dashboard }) {
  const figures = [
    { label: "Gross sales", value: money.format(dashboard.grossSales) },
    { label: "Baskets", value: String(dashboard.baskets) },
    { label: "Average basket", value: money.format(dashboard.averageBasket) },
    { label: "Refunds", value: money.format(dashboard.refunds) },
    { label: "Gross margin", value: `${money.format(dashboard.grossMargin)} (${dashboard.grossMarginPercent}%)` },
    { label: "Stock value", value: dashboard.stockValue === null ? "Not valued yet" : money.format(dashboard.stockValue) },
    { label: "Dead stock", value: `${dashboard.deadStockLines} lines · ${money.format(dashboard.deadStockValue)}` },
    { label: "Near expiry", value: money.format(dashboard.nearExpiryValue) },
  ];
  return (
    <>
      <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4" data-testid="dashboard-figures">
        {figures.map((figure) => (
          <Card key={figure.label}>
            <CardHeader>
              <CardDescription>{figure.label}</CardDescription>
              <CardTitle className="text-2xl tabular-nums">{figure.value}</CardTitle>
            </CardHeader>
          </Card>
        ))}
      </div>
      <Card>
        <CardHeader>
          <CardTitle>Top movers today</CardTitle>
        </CardHeader>
        <CardContent>
          {dashboard.topMovers.length === 0 ? (
            <p className="text-muted-foreground">No sales yet today.</p>
          ) : (
            <ul className="divide-y">
              {dashboard.topMovers.map((mover) => (
                <li key={mover.productId} className="flex justify-between gap-4 py-3">
                  <span>{mover.productName ?? mover.sku ?? mover.productId}</span>
                  <span className="tabular-nums text-muted-foreground">
                    {mover.quantity} sold · {money.format(mover.netSales)}
                  </span>
                </li>
              ))}
            </ul>
          )}
        </CardContent>
      </Card>
    </>
  );
}
