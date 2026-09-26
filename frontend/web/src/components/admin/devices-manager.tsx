"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { type FormEvent, useState } from "react";
import { toast } from "sonner";

import { FormError, problemErrors, Section, SelectInput } from "@/components/admin/form-parts";
import { DataTable } from "@/components/admin/page-parts";
import { Field } from "@/components/field";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { PageOf } from "@/lib/api/admin-schemas";
import { api } from "@/lib/api/client";
import { type Device, DeviceRegistrationSchema, DeviceSchema } from "@/lib/api/device-schemas";
import { formatWhen } from "@/lib/format";

const STATUS: Record<Device["status"], string> = {
  PENDING: "Waiting for its code",
  EXPIRED: "Code expired",
  ACTIVE: "Registered",
  REVOKED: "Revoked",
};

type Registration = { name: string; code: string; expiresAt: string };

/**
 * A branch's devices. Registering one gives a code, shown once, to type on that device's
 * "Register this device" page; revoking one signs out everyone using it.
 */
export function DevicesManager({ branches, canManage }: { branches: { id: string; name: string }[]; canManage: boolean }) {
  const client = useQueryClient();
  const [branchId, setBranchId] = useState(branches[0]?.id ?? "");
  const [registration, setRegistration] = useState<Registration | null>(null);
  const [revoking, setRevoking] = useState<Device | null>(null);

  const list = useQuery({
    queryKey: ["devices", branchId],
    queryFn: () => api(`devices?${new URLSearchParams({ branchId, size: "100" })}`, PageOf(DeviceSchema)),
    enabled: Boolean(branchId),
  });
  const refresh = () => client.invalidateQueries({ queryKey: ["devices"] });

  return (
    <div className="grid gap-8">
      <div className="max-w-sm">
        <SelectInput id="device-branch" label="Branch" value={branchId} onChange={setBranchId} options={branches.map((b) => ({ value: b.id, label: b.name }))} />
      </div>
      {canManage && branchId ? (
        <RegisterDevice
          branchId={branchId}
          onRegistered={async (value) => {
            setRegistration(value);
            await refresh();
          }}
        />
      ) : null}
      <Section title="This branch's devices" id="devices">
        {list.error ? <FormError message="The devices could not be loaded." /> : null}
        {list.data ? (
          <DataTable headings={["Name", "Status", "Registered", "Last sign-in", ""]} empty={list.data.content.length === 0}>
            {list.data.content.map((device) => (
              <tr key={device.id} className="border-t" data-testid="device-row">
                <td className="px-3 py-2 font-medium">{device.name}</td>
                <td className="px-3 py-2" data-testid="device-status">
                  {STATUS[device.status]}
                  {device.status === "PENDING" && device.enrolmentExpiresAt ? (
                    <span className="block text-xs text-muted-foreground">until {formatWhen(device.enrolmentExpiresAt)}</span>
                  ) : null}
                  {device.revokedReason ? <span className="block text-xs text-muted-foreground">{device.revokedReason}</span> : null}
                </td>
                <td className="px-3 py-2">{formatWhen(device.enrolledAt)}</td>
                <td className="px-3 py-2">{formatWhen(device.lastSeenAt)}</td>
                <td className="px-3 py-2 text-right">
                  {canManage && device.status !== "REVOKED" ? (
                    <Button size="sm" variant="ghost" onClick={() => setRevoking(device)}>
                      {device.status === "ACTIVE" ? "Revoke" : "Cancel"}
                    </Button>
                  ) : null}
                </td>
              </tr>
            ))}
          </DataTable>
        ) : null}
      </Section>
      {registration ? <CodeDialog registration={registration} onClose={() => setRegistration(null)} /> : null}
      {revoking ? <RevokeDialog device={revoking} onClose={() => setRevoking(null)} onDone={refresh} /> : null}
    </div>
  );
}

function RegisterDevice({ branchId, onRegistered }: { branchId: string; onRegistered: (registration: Registration) => Promise<void> }) {
  const [name, setName] = useState("");
  const [errors, setErrors] = useState<Record<string, string>>({});
  const register = useMutation({
    mutationFn: () => api("devices", DeviceRegistrationSchema, { method: "POST", json: { branchId, name: name.trim() } }),
    onSuccess: async (created) => {
      setErrors({});
      setName("");
      await onRegistered({ name: created.device.name, code: created.enrolmentCode, expiresAt: created.expiresAt });
    },
    onError: (failure) => setErrors(problemErrors(failure, "The device was not registered.")),
  });
  return (
    <Section title="Register a device" id="register-device">
      <p className="text-sm text-muted-foreground">
        Name it as staff will know it - &quot;Till 3&quot;, &quot;Back office PC&quot;. You get a code to type on that device.
      </p>
      <form
        onSubmit={(event: FormEvent) => {
          event.preventDefault();
          register.mutate();
        }}
        className="flex max-w-xl flex-wrap items-end gap-3"
        aria-label="Register a device"
      >
        <div className="min-w-60 flex-1">
          <Field id="device-name" label="Name" value={name} onChange={(event) => setName(event.target.value)} error={errors.name} required />
        </div>
        <Button type="submit" disabled={register.isPending}>
          Register
        </Button>
      </form>
      <FormError message={errors.form} />
    </Section>
  );
}

function CodeDialog({ registration, onClose }: { registration: Registration; onClose: () => void }) {
  return (
    <Dialog open onOpenChange={(open) => (!open ? onClose() : undefined)}>
      <DialogContent className="sm:max-w-md">
        <DialogHeader>
          <DialogTitle>Code for {registration.name}</DialogTitle>
          <DialogDescription>
            On that device, open the POS, choose <strong>Register this device</strong> on the sign-in page and type this code.
            It works once, until {formatWhen(registration.expiresAt)}, and is not shown again.
          </DialogDescription>
        </DialogHeader>
        <p className="py-4 text-center font-mono text-4xl font-semibold tracking-widest" data-testid="enrolment-code">
          {registration.code}
        </p>
        <Button onClick={onClose}>Done</Button>
      </DialogContent>
    </Dialog>
  );
}

function RevokeDialog({ device, onClose, onDone }: { device: Device; onClose: () => void; onDone: () => Promise<void> }) {
  const [reason, setReason] = useState("");
  const [error, setError] = useState<string | undefined>();
  const revoke = useMutation({
    mutationFn: () => api(`devices/${device.id}/revoke`, DeviceSchema, { method: "POST", json: { reason } }),
    onSuccess: async () => {
      toast.success(`${device.name} revoked: nobody can sign in on it now.`);
      await onDone();
      onClose();
    },
    onError: (failure) => setError(problemErrors(failure, "It was not revoked.").form ?? problemErrors(failure, "").reason),
  });
  return (
    <Dialog open onOpenChange={(open) => (!open ? onClose() : undefined)}>
      <DialogContent className="sm:max-w-md">
        <DialogHeader>
          <DialogTitle>
            {device.status === "ACTIVE" ? "Revoke" : "Cancel"} {device.name}
          </DialogTitle>
          <DialogDescription>
            {device.status === "ACTIVE"
              ? "Everyone signed in on it is signed out, and nobody can sign in on it again. To use it again, register it anew."
              : "Its code stops working."}
          </DialogDescription>
        </DialogHeader>
        <form
          onSubmit={(event: FormEvent) => {
            event.preventDefault();
            revoke.mutate();
          }}
          className="grid gap-3"
        >
          <Field id="device-reason" label="Why" value={reason} onChange={(event) => setReason(event.target.value)} required />
          <FormError message={error} />
          <Button type="submit" variant="destructive" disabled={revoke.isPending}>
            {device.status === "ACTIVE" ? "Revoke it" : "Cancel it"}
          </Button>
        </form>
      </DialogContent>
    </Dialog>
  );
}
