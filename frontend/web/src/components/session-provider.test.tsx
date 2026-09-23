// @vitest-environment jsdom
import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";

import { Can, type ClientSession, SessionProvider } from "./session-provider";

const session = (permissions: string[]): ClientSession => ({
  fullName: "Wanjiku Kamau",
  email: "wanjiku@example.test",
  roles: [],
  permissions,
  branches: [],
  activeBranch: null,
});

describe("<Can>", () => {
  it("shows what the user holds a permission for", () => {
    render(
      <SessionProvider session={session(["sale:void"])}>
        <Can permission="sale:void">Void sale</Can>
      </SessionProvider>,
    );
    expect(screen.getByText("Void sale")).toBeInTheDocument();
  });

  it("hides it otherwise, showing the fallback if there is one", () => {
    render(
      <SessionProvider session={session(["sale:create"])}>
        <Can permission="sale:void" fallback="Ask a supervisor">
          Void sale
        </Can>
      </SessionProvider>,
    );
    expect(screen.queryByText("Void sale")).not.toBeInTheDocument();
    expect(screen.getByText("Ask a supervisor")).toBeInTheDocument();
  });

  it("accepts any one of several permissions", () => {
    render(
      <SessionProvider session={session(["report:view:branch"])}>
        <Can permission={["report:view", "report:view:branch"]}>Reports</Can>
      </SessionProvider>,
    );
    expect(screen.getByText("Reports")).toBeInTheDocument();
  });
});
