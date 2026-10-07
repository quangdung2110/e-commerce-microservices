"use client";

import Link from "next/link";
import { FormEvent, useMemo, useState } from "react";
import { useRouter } from "next/navigation";
import { Elements, PaymentElement, useElements, useStripe } from "@stripe/react-stripe-js";
import { loadStripe, type StripeError } from "@stripe/stripe-js";
import { ArrowLeft, CreditCard, LoaderCircle } from "lucide-react";

import { Alert } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { EmptyState } from "@/components/ui/empty-state";
import { ErrorState } from "@/components/ui/error-state";
import { Skeleton } from "@/components/ui/skeleton";
import { useOrder, usePaymentAttempt } from "@/hooks/use-orders";
import type { PaymentAttempt } from "@/lib/api/order";
import { formatDateTime, formatVND } from "@/lib/format";

export function PaymentContent({ orderId, requestedAttempt }: { orderId: string; requestedAttempt: number | null }) {
  const order = useOrder(orderId);
  const attemptNumber = requestedAttempt ?? order.data?.payment.active_attempt_number ?? null;
  const attempt = usePaymentAttempt(orderId, attemptNumber);

  if (order.isPending || (attemptNumber && attempt.isPending)) return <PaymentSkeleton />;
  if (order.isError || !order.data) {
    return <PaymentShell orderId={orderId}><ErrorState error={order.error} onRetry={() => void order.refetch()} /></PaymentShell>;
  }
  if (!attemptNumber) {
    return <PaymentShell orderId={orderId}><EmptyState icon={CreditCard} title="Chưa có lượt thanh toán" description="Đơn hàng này chưa có lượt thanh toán đang hoạt động." action={<Link href={`/orders/${orderId}`} className="focus-ring rounded-lg text-[14px] font-medium text-clay-strong hover:underline">Về chi tiết đơn</Link>} /></PaymentShell>;
  }
  if (attempt.isError || !attempt.data) {
    return <PaymentShell orderId={orderId}><ErrorState error={attempt.error} onRetry={() => void attempt.refetch()} /></PaymentShell>;
  }
  if (attempt.data.status !== "PENDING" || !attempt.data.client_secret || !attempt.data.publishable_key) {
    return (
      <PaymentShell orderId={orderId}>
        <Alert variant={attempt.data.status === "SUCCESS" ? "success" : "warning"} title={attempt.data.status === "SUCCESS" ? "Lượt thanh toán đã thành công" : "Lượt thanh toán không còn sử dụng được"}>
          {attempt.data.status === "FAILED" ? "Bạn có thể quay lại chi tiết đơn để tạo lượt thanh toán mới nếu đơn vẫn đủ điều kiện." : "Hãy tải lại chi tiết đơn để xem trạng thái mới nhất."}
        </Alert>
        <Link href={`/orders/${orderId}`} className="focus-ring inline-flex min-h-11 items-center rounded-lg text-[14px] font-medium text-clay-strong hover:underline">Về chi tiết đơn hàng</Link>
      </PaymentShell>
    );
  }

  return <StripePayment orderId={orderId} attempt={attempt.data} />;
}

function StripePayment({ orderId, attempt }: { orderId: string; attempt: PaymentAttempt }) {
  const stripePromise = useMemo(() => loadStripe(attempt.publishable_key!), [attempt.publishable_key]);
  const options = useMemo(() => ({
    clientSecret: attempt.client_secret!,
    locale: "vi" as const,
  }), [attempt.client_secret]);

  return (
    <PaymentShell orderId={orderId}>
      <div className="grid gap-6 lg:grid-cols-[1fr_320px]">
        <Card>
          <CardHeader>
            <CardTitle>Thông tin thanh toán</CardTitle>
            <p className="text-caption text-muted-foreground">Thông tin thẻ được gửi trực tiếp tới Stripe và không đi qua máy chủ TechNova.</p>
          </CardHeader>
          <CardContent>
            <Elements stripe={stripePromise} options={options}>
              <StripePaymentForm orderId={orderId} attemptNumber={attempt.attempt_number} />
            </Elements>
          </CardContent>
        </Card>

        <Card className="h-fit">
          <CardHeader><CardTitle>Tóm tắt</CardTitle></CardHeader>
          <CardContent className="space-y-4">
            <div><p className="text-caption text-muted-foreground">Đơn hàng</p><p className="font-mono text-[14px]">#{orderId}</p></div>
            <div><p className="text-caption text-muted-foreground">Lượt thanh toán</p><p>{attempt.attempt_number}</p></div>
            <div><p className="text-caption text-muted-foreground">Số tiền</p><p className="font-serif text-headline-4 font-normal tabular-nums">{formatVND(attempt.amount.amount_minor)}</p></div>
            {attempt.expires_at ? <div><p className="text-caption text-muted-foreground">Có hiệu lực đến</p><p>{formatDateTime(attempt.expires_at)}</p></div> : null}
          </CardContent>
        </Card>
      </div>
    </PaymentShell>
  );
}

function StripePaymentForm({ orderId, attemptNumber }: { orderId: string; attemptNumber: number }) {
  const stripe = useStripe();
  const elements = useElements();
  const router = useRouter();
  const [submitting, setSubmitting] = useState(false);
  const [message, setMessage] = useState<string | null>(null);

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!stripe || !elements || submitting) return;
    setSubmitting(true);
    setMessage(null);

    const submitResult = await elements.submit();
    if (submitResult.error) {
      setMessage(stripeErrorMessage(submitResult.error));
      setSubmitting(false);
      return;
    }

    const returnUrl = `${window.location.origin}/orders/${orderId}/payment/complete?attempt=${attemptNumber}`;
    const result = await stripe.confirmPayment({
      elements,
      confirmParams: { return_url: returnUrl },
      redirect: "if_required",
    });

    if (result.error) {
      setMessage(stripeErrorMessage(result.error));
      setSubmitting(false);
      return;
    }
    router.push(`/orders/${orderId}/payment/complete?attempt=${attemptNumber}`);
  }

  return (
    <form onSubmit={submit} className="space-y-6">
      <PaymentElement options={{ layout: "tabs" }} />
      {message ? <Alert variant="danger">{message}</Alert> : null}
      <Button type="submit" variant="clay" className="w-full" disabled={!stripe || !elements || submitting}>
        {submitting ? <LoaderCircle className="animate-spin" aria-hidden /> : <CreditCard aria-hidden />}
        {submitting ? "Đang xác nhận thanh toán…" : "Thanh toán an toàn"}
      </Button>
    </form>
  );
}

function stripeErrorMessage(error: StripeError): string {
  if (error.code === "card_declined") return "Thẻ đã bị từ chối. Hãy kiểm tra lại hoặc dùng thẻ khác.";
  if (error.code === "expired_card") return "Thẻ đã hết hạn. Hãy dùng thẻ khác.";
  if (error.code === "incorrect_cvc") return "Mã bảo mật của thẻ chưa đúng.";
  if (error.type === "validation_error") return "Thông tin thanh toán chưa hợp lệ. Hãy kiểm tra các trường được đánh dấu.";
  return "Stripe chưa thể xác nhận thanh toán. Hãy kiểm tra thông tin và thử lại.";
}

function PaymentShell({ orderId, children }: { orderId: string; children: React.ReactNode }) {
  return (
    <main className="mx-auto w-full max-w-small space-y-8 px-6 py-10 md:px-8 md:py-14">
      <Link href={`/orders/${orderId}`} className="focus-ring inline-flex min-h-11 items-center gap-2 rounded-lg text-[14px] text-muted-foreground hover:text-foreground"><ArrowLeft className="size-4" aria-hidden />Chi tiết đơn hàng</Link>
      <header className="space-y-2"><h1 className="font-serif text-headline-2 font-normal">Thanh toán đơn hàng</h1><p className="text-[15px] text-muted-foreground">Hoàn tất thanh toán cho đơn #{orderId} qua Stripe.</p></header>
      {children}
    </main>
  );
}

function PaymentSkeleton() {
  return <main className="mx-auto w-full max-w-small space-y-8 px-6 py-10 md:px-8 md:py-14" aria-busy="true" aria-label="Đang tải thanh toán"><Skeleton className="h-5 w-36" /><Skeleton className="h-10 w-72" /><div className="grid gap-6 lg:grid-cols-[1fr_320px]"><Skeleton className="h-96 rounded-xl" /><Skeleton className="h-64 rounded-xl" /></div></main>;
}
