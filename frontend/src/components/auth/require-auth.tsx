"use client";

import { useEffect } from "react";
import Link from "next/link";
import { usePathname, useRouter } from "next/navigation";
import { ShieldAlert } from "lucide-react";

import { buttonVariants } from "@/components/ui/button";
import { Skeleton } from "@/components/ui/skeleton";
import { useAuth } from "@/hooks/use-auth";
import type { Role } from "@/lib/auth/roles";
import { useAuthStore } from "@/store/useAuthStore";

/**
 * Đưa người chưa đăng nhập về trang đăng nhập, kèm `next` để quay lại đúng chỗ.
 *
 * Tài khoản vừa bị xoá thì không có "chỗ cũ" nào để quay lại.
 */
function useRedirectAnonymous() {
  const router = useRouter();
  const pathname = usePathname();
  const { status } = useAuth();
  const signOutReason = useAuthStore((state) => state.signOutReason);

  useEffect(() => {
    if (status !== "anonymous") return;
    router.replace(
      signOutReason === "account_deleted"
        ? "/login"
        : `/login?next=${encodeURIComponent(pathname)}`
    );
  }, [status, signOutReason, pathname, router]);

  return status;
}

function PageSkeleton() {
  return (
    <div
      className="mx-auto w-full max-w-small space-y-8 px-6 py-10 md:px-8 md:py-14"
      aria-busy="true"
    >
      <div className="space-y-2">
        <Skeleton className="h-10 w-64" />
        <Skeleton className="h-5 w-96 max-w-full" />
      </div>
      <Skeleton className="h-64 w-full rounded-xl" />
    </div>
  );
}

/**
 * Chặn các màn hình cần đăng nhập.
 *
 * Đây là hàng rào **trải nghiệm**, không phải hàng rào bảo mật: dữ liệu thật
 * được API Gateway bảo vệ bằng JWT, và mọi request thiếu danh tính đều bị từ
 * chối ở đó. Việc của component này chỉ là đưa người dùng tới trang đăng nhập
 * thay vì để họ nhìn một màn hình lỗi 401.
 *
 * Trạng thái "loading" phải được render riêng: coi nó như "chưa đăng nhập" sẽ
 * đá người dùng ra ngoài mỗi lần tải lại trang, trước khi cookie refresh kịp
 * đổi lấy access token mới.
 */
export function RequireAuth({ children }: { children: React.ReactNode }) {
  const status = useRedirectAnonymous();
  if (status !== "authenticated") return <PageSkeleton />;
  return <>{children}</>;
}

/**
 * Như `RequireAuth`, cộng thêm yêu cầu một role. Người đã đăng nhập nhưng thiếu
 * role thấy trạng thái 403 tại chỗ — chuyển hướng họ về trang đăng nhập là vô
 * nghĩa vì đăng nhập lại cũng không có thêm quyền.
 */
export function RequireRole({
  role,
  children,
}: {
  role: Role;
  children: React.ReactNode;
}) {
  const status = useRedirectAnonymous();
  const { hasRole } = useAuth();

  if (status !== "authenticated") return <PageSkeleton />;
  if (!hasRole(role)) return <Forbidden />;
  return <>{children}</>;
}

function Forbidden() {
  return (
    <div className="mx-auto flex min-h-[60svh] w-full max-w-narrow flex-col items-center justify-center gap-4 px-6 py-16 text-center">
      <ShieldAlert className="size-8 text-muted-foreground" aria-hidden />
      <h1 className="text-headline-4">Bạn không có quyền vào trang này</h1>
      <p className="max-w-prose-narrow text-[15px] text-muted-foreground">
        Trang này chỉ dành cho quản trị viên. Nếu bạn cần quyền truy cập, hãy liên hệ
        người quản lý hệ thống.
      </p>
      <Link href="/" className={buttonVariants({ variant: "outline" })}>
        Về trang chủ
      </Link>
    </div>
  );
}
