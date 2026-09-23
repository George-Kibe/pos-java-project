// @vitest-environment jsdom
import { act, fireEvent, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { OtpStep } from "./otp-step";

vi.mock("sonner", () => ({ toast: { success: vi.fn(), error: vi.fn() } }));

function answer(status: number, body: unknown) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": status >= 400 ? "application/problem+json" : "application/json" },
  });
}

const fetchMock = vi.fn<typeof fetch>();

beforeEach(() => {
  vi.stubGlobal("fetch", fetchMock);
  fetchMock.mockReset();
});
afterEach(() => {
  vi.useRealTimers();
  vi.unstubAllGlobals();
});

function renderStep(onVerified = vi.fn(), startCoolingDown = true) {
  render(
    <OtpStep
      email="new@example.test"
      codeLength={6}
      resendCooldownSeconds={60}
      startCoolingDown={startCoolingDown}
      onVerified={onVerified}
    />,
  );
  return onVerified;
}

describe("entering the emailed code", () => {
  it("accepts a pasted code and submits it without another click", async () => {
    fetchMock.mockResolvedValue(answer(200, { message: "Verified" }));
    const onVerified = renderStep();

    const input = screen.getByRole("textbox", { name: "Verification code" });
    await userEvent.click(input);
    await userEvent.paste("482913");

    await waitFor(() => expect(onVerified).toHaveBeenCalled());
    const [url, init] = fetchMock.mock.calls[0];
    expect(url).toBe("/api/auth/verify-otp");
    expect(JSON.parse(String(init?.body))).toEqual({ email: "new@example.test", code: "482913" });
  });

  it("shows why a wrong code failed and clears it for another try", async () => {
    fetchMock.mockResolvedValue(
      answer(400, { status: 400, code: "auth.otp_invalid", detail: "That code is not right." }),
    );
    const onVerified = renderStep();

    const input = screen.getByRole("textbox", { name: "Verification code" });
    await userEvent.click(input);
    await userEvent.paste("000000");

    expect(await screen.findByRole("alert")).toHaveTextContent("That code is not right.");
    expect(onVerified).not.toHaveBeenCalled();
    expect(input).toHaveValue("");
  });

  it("counts the resend cooldown down, visibly, before allowing another code", async () => {
    vi.useFakeTimers();
    renderStep();

    const resend = screen.getByRole("button", { name: /send a new code in 60s/i });
    expect(resend).toBeDisabled();

    // One act per second: each tick re-renders, and only then is the next tick scheduled.
    for (let second = 0; second < 60; second++) {
      await act(() => vi.advanceTimersByTimeAsync(1000));
    }
    expect(screen.queryByRole("button", { name: /in \d+s/ })).not.toBeInTheDocument();

    expect(screen.getByRole("button", { name: "Send a new code" })).toBeEnabled();
  });

  it("asks for a new code and starts the cooldown again", async () => {
    fetchMock.mockResolvedValue(answer(200, { message: "Sent" }));
    renderStep(vi.fn(), false);

    fireEvent.click(screen.getByRole("button", { name: "Send a new code" }));

    await waitFor(() => expect(fetchMock).toHaveBeenCalled());
    expect(fetchMock.mock.calls[0][0]).toBe("/api/auth/resend-otp");
    expect(screen.getByRole("button", { name: /send a new code in \d+s/i })).toBeDisabled();
  });
});
