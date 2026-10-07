import type { Metadata } from "next";

import { AdminShell } from "@/components/admin/admin-shell";
import { RequireRole } from "@/components/auth/require-auth";
import { ROLES } from "@/lib/auth/roles";

export const metadata: Metadata = {
  title: "Quản trị",
  description: "Khu vực quản trị TechNova.",
};

export default function AdminLayout({ children }: { children: React.ReactNode }) {
  return (
    <RequireRole role={ROLES.ADMIN}>
      <AdminShell>{children}</AdminShell>
    </RequireRole>
  );
}
