"use client";

import Link from "next/link";
import { FormEvent, useCallback, useEffect, useMemo, useRef, useState, useSyncExternalStore } from "react";
import { useRouter } from "next/navigation";
import { Elements, PaymentElement, useElements, useStripe } from "@stripe/react-stripe-js";
import { loadStripe, type StripeError } from "@stripe/stripe-js";
import { ArrowLeft, CreditCard, LoaderCircle } from "lucide-react";
import { useTheme } from "next-themes";

import { Alert } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { EmptyState } from "@/components/ui/empty-state";
import { ErrorState } from "@/components/ui/error-state";
import { Skeleton } from "@/components/ui/skeleton";
import { useOrder, usePaymentAttempt } from "@/hooks/use-orders";
import type { OrderDetail, PaymentAttempt } from "@/lib/api/order";
import { formatDateTime, formatVND } from "@/lib/format";
import { canConfirmPayment, confirmationKey, confirmationUnknown, markConfirmationUnknown, paymentExpiresAt, subscribeConfirmation } from "@/lib/payment-confirmation";

type PaymentRead = () => Promise<{ order: OrderDetail; attempt: PaymentAttempt }>;

export function PaymentContent({ orderId, requestedAttempt }: { orderId: string; requestedAttempt: number | null }) {
  const order = useOrder(orderId, { pollPayment: true });
  const attemptNumber = requestedAttempt ?? order.data?.payment.active_attempt_number ?? null;
  const attempt = usePaymentAttempt(orderId, attemptNumber, { poll: true });
  const expiresAt = order.data && attempt.data ? paymentExpiresAt(order.data, attempt.data) : null;
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    if (expiresAt === null || now >= expiresAt) return;
    const timer = setTimeout(() => setNow(Date.now()), Math.min(Math.max(0, expiresAt - Date.now()), 2_147_483_647));
    return () => clearTimeout(timer);
  }, [expiresAt, now]);

  const readPayment: PaymentRead = async () => {
    const [freshOrder, freshAttempt] = await Promise.all([order.refetch(), attempt.refetch()]);
    if (freshOrder.isError || freshAttempt.isError || !freshOrder.data || !freshAttempt.data) {
      throw new Error("Payment state could not be verified");
    }
    return { order: freshOrder.data, attempt: freshAttempt.data };
  };

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
  if (!canConfirmPayment(order.data, attempt.data, now)) {
    return (
      <PaymentShell orderId={orderId}>
        <Alert variant={attempt.data.status === "SUCCESS" ? "success" : "warning"} title={attempt.data.status === "SUCCESS" ? "Lượt thanh toán đã thành công" : "Lượt thanh toán không còn sử dụng được"}>
          {attempt.data.status === "FAILED" ? "Bạn có thể quay lại chi tiết đơn để tạo lượt thanh toán mới nếu đơn vẫn đủ điều kiện." : "Hãy tải lại chi tiết đơn để xem trạng thái mới nhất."}
        </Alert>
        <Link href={`/orders/${orderId}`} className="focus-ring inline-flex min-h-11 items-center rounded-lg text-[14px] font-medium text-clay-strong hover:underline">Về chi tiết đơn hàng</Link>
      </PaymentShell>
    );
  }

  return <StripePayment key={attempt.data.payment_transaction_id} orderId={orderId} attempt={attempt.data} expiresAt={expiresAt!} readPayment={readPayment} />;
}

function StripePayment({ orderId, attempt, expiresAt, readPayment }: { orderId: string; attempt: PaymentAttempt; expiresAt: number; readPayment: PaymentRead }) {
  const { resolvedTheme } = useTheme();
  const dark = resolvedTheme === "dark";
  const [sdkFailed, setSdkFailed] = useState(false);
  const onUnavailable = useCallback(() => setSdkFailed(true), []);
  const stripePromise = useMemo(() => {
    try { return loadStripe(attempt.publishable_key!).catch(() => null); }
    catch { return Promise.resolve(null); }
  }, [attempt.publishable_key]);
  useEffect(() => {
    let active = true;
    void stripePromise.then((stripe) => { if (active && !stripe) onUnavailable(); });
    return () => { active = false; };
  }, [stripePromise, onUnavailable]);
  const options = useMemo(() => ({
    clientSecret: attempt.client_secret!,
    locale: "vi" as const,
    appearance: {
      theme: dark ? "night" as const : "stripe" as const,
      variables: {
        colorBackground: dark ? "#1f1e1d" : "#ffffff",
        colorText: dark ? "#faf9f5" : "#141413",
        colorTextSecondary: dark ? "#b0aea5" : "#5e5d59",
        colorTextPlaceholder: dark ? "#b0aea5" : "#5e5d59",
        colorPrimary: dark ? "#d97757" : "#9c4d32",
        colorDanger: dark ? "#e0736a" : "#bf4d43",
        borderRadius: "8px",
      },
    },
  }), [attempt.client_secret, dark]);

  return (
    <PaymentShell orderId={orderId}>
      <div className="grid gap-6 lg:grid-cols-[1fr_320px]">
        <Card>
          <CardHeader>
            <CardTitle>Thông tin thanh toán</CardTitle>
            <p className="text-caption text-muted-foreground">Thông tin thẻ được gửi trực tiếp tới Stripe và không đi qua máy chủ TechNova.</p>
          </CardHeader>
          <CardContent>
            {sdkFailed ? <div className="space-y-4">
              <Alert variant="danger" title="Không tải được Stripe">Hãy kiểm tra kết nối và tải lại trang thanh toán. Đơn hàng và lượt thanh toán hiện tại được giữ nguyên.</Alert>
              <Button type="button" variant="outline" className="w-full" onClick={() => window.location.reload()}>Thử tải lại Stripe</Button>
            </div> : <Elements key={attempt.payment_transaction_id} stripe={stripePromise} options={options}>
              <StripePaymentForm orderId={orderId} attempt={attempt} readPayment={readPayment} onUnavailable={onUnavailable} />
            </Elements>}
          </CardContent>
        </Card>

        <Card className="h-fit">
          <CardHeader><CardTitle>Tóm tắt</CardTitle></CardHeader>
          <CardContent className="space-y-4">
            <div><p className="text-caption text-muted-foreground">Đơn hàng</p><p className="font-mono text-[14px]">#{orderId}</p></div>
            <div><p className="text-caption text-muted-foreground">Lượt thanh toán</p><p>{attempt.attempt_number}</p></div>
            <div><p className="text-caption text-muted-foreground">Số tiền</p><p className="font-serif text-headline-4 font-normal tabular-nums">{formatVND(attempt.amount.amount_minor)}</p></div>
            <div><p className="text-caption text-muted-foreground">Hạn thanh toán</p><p>{formatDateTime(new Date(expiresAt).toISOString())}</p></div>
          </CardContent>
        </Card>
      </div>
    </PaymentShell>
  );
}

function StripePaymentForm({ orderId, attempt, readPayment, onUnavailable }: { orderId: string; attempt: PaymentAttempt; readPayment: PaymentRead; onUnavailable: () => void }) {
  const stripe = useStripe();
  const elements = useElements();
  const router = useRouter();
  const [elementReady, setElementReady] = useState(false);
  const onReady = useCallback(() => setElementReady(true), []);
  const [submitting, setSubmitting] = useState(false);
  const [message, setMessage] = useState<string | null>(null);
  const inFlight = useRef(false);
  const key = confirmationKey(orderId, attempt.attempt_number);
  const unknown = useSyncExternalStore(subscribeConfirmation, () => confirmationUnknown(key), () => true);
  useEffect(() => {
    if (stripe && elements && elementReady) return;
    const timer = setTimeout(onUnavailable, 20_000);
    return () => clearTimeout(timer);
  }, [stripe, elements, elementReady, onUnavailable]);

  async function recoverResult() {
    try {
      const fresh = await readPayment();
      if (fresh.order.payment_status === "PAID") {
        markConfirmationUnknown(key, false);
        router.push(`/orders/${orderId}/payment/complete?attempt=${attempt.attempt_number}`);
      } else if (fresh.order.order_status !== "PENDING_PAYMENT" || fresh.attempt.status !== "PENDING") {
        markConfirmationUnknown(key, false);
        router.push(`/orders/${orderId}`);
      }
      // A fresh PENDING still cannot prove whether Stripe received confirm.
      // Keep the marker and wait for a terminal fact, rather than send again.
    } catch {
      setMessage("Chưa đọc được kết quả thanh toán. Hãy kiểm tra lại trạng thái; không thanh toán lại lúc này.");
    }
  }

  async function checkResult() {
    if (inFlight.current) return;
    inFlight.current = true;
    setSubmitting(true);
    try {
      await recoverResult();
    } finally {
      inFlight.current = false;
      setSubmitting(false);
    }
  }

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!stripe || !elements || !elementReady || inFlight.current || confirmationUnknown(key)) return;
    inFlight.current = true;
    setSubmitting(true);
    setMessage(null);

    let confirmStarted = false;
    try {
      const submitResult = await elements.submit();
      if (submitResult.error) {
        setMessage(stripeErrorMessage(submitResult.error));
        return;
      }
      const fresh = await readPayment();
      if (!canConfirmPayment(fresh.order, fresh.attempt, Date.now())
        || fresh.attempt.payment_transaction_id !== attempt.payment_transaction_id
        || fresh.attempt.client_secret !== attempt.client_secret) {
        setMessage("Lượt thanh toán đã thay đổi hoặc hết hạn. Hãy xem trạng thái mới nhất trong chi tiết đơn hàng.");
        return;
      }
      // Mark before crossing the provider boundary, including a tab close or reload.
      markConfirmationUnknown(key, true);
      confirmStarted = true;
      const returnUrl = `${window.location.origin}/orders/${orderId}/payment/complete?attempt=${attempt.attempt_number}`;
      const result = await stripe.confirmPayment({ elements, confirmParams: { return_url: returnUrl }, redirect: "if_required" });
      if (result.error) {
        if (["card_error", "validation_error"].includes(result.error.type)
          || result.error.code === "payment_intent_authentication_failure") {
          markConfirmationUnknown(key, false);
          setMessage(stripeErrorMessage(result.error));
          return;
        }
        // Transport/API failures have an unknown outcome. Read the source of
        // truth before showing any further action, even if the read also fails.
        await recoverResult();
        return;
      }
      router.push(`/orders/${orderId}/payment/complete?attempt=${attempt.attempt_number}`);
    } catch {
      if (confirmStarted) {
        // SDK promise rejection is also ambiguous; never unlock confirm.
        await recoverResult();
      } else {
        setMessage("Chưa thể kiểm tra lượt thanh toán. Hãy kiểm tra kết nối và thử lại; yêu cầu thanh toán chưa được gửi.");
      }
    } finally {
      inFlight.current = false;
      setSubmitting(false);
    }
  }

  return (
    <form onSubmit={submit} className="space-y-6">
      <PaymentElement options={{ layout: "tabs" }} onLoadError={onUnavailable} onReady={onReady} />
      {!stripe || !elements || !elementReady ? <Alert title="Đang tải kết nối Stripe">Vui lòng chờ trong khi tải thông tin thanh toán.</Alert> : null}
      {unknown ? <Alert title="Đang kiểm tra kết quả thanh toán">Kết quả thanh toán chưa được xác nhận. Không thanh toán lại lúc này; hãy kiểm tra trạng thái đơn hàng.</Alert> : null}
      {message ? <Alert variant="danger">{message}</Alert> : null}
      {unknown ? <Button type="button" variant="outline" className="w-full" disabled={submitting} onClick={() => void checkResult()}>Kiểm tra kết quả thanh toán</Button> : null}
      <Button type="submit" variant="clay" className="w-full" disabled={!stripe || !elements || !elementReady || submitting || unknown}>
        {submitting ? <LoaderCircle className="animate-spin" aria-hidden /> : <CreditCard aria-hidden />}
        {submitting ? "Đang xác nhận thanh toán…" : "Thanh toán an toàn"}
      </Button>
    </form>
  );
}

function stripeErrorMessage(error: StripeError): string {
  if (error.code === "payment_intent_authentication_failure") return "Xác thực thanh toán chưa hoàn tất. Bạn có thể thử lại hoặc dùng thẻ khác.";
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
