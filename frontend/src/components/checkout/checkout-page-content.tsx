"use client";

import Link from "next/link";
import { useEffect, useRef, useState } from "react";
import { useRouter } from "next/navigation";
import { ArrowLeft, Clock3, CreditCard, LoaderCircle, MapPin } from "lucide-react";
import { Alert } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { EmptyState } from "@/components/ui/empty-state";
import { ErrorState } from "@/components/ui/error-state";
import { Skeleton } from "@/components/ui/skeleton";
import { StorefrontImage } from "@/components/catalog/storefront-image";
import { CheckoutVoucherTray } from "@/components/checkout/checkout-voucher-tray";
import { useCheckout } from "@/hooks/use-checkout";
import { useCheckoutVouchers } from "@/hooks/use-checkout-vouchers";
import { useAddresses } from "@/hooks/use-user";
import { checkoutProblem, isSubmissionReady, submissionDestination, submissionTokenFromUrl } from "@/lib/checkout";
import { voucherReason } from "@/lib/checkout-voucher";
import { isRetryable, problemCode, retryAfterSeconds } from "@/lib/api/problem";
import { formatAddressLine } from "@/lib/constants/address";
import { formatDateTime, formatVND } from "@/lib/format";
import { useAuthStore } from "@/store/useAuthStore";

export function CheckoutPageContent({ initialToken }: { initialToken: string | null }) {
  const accountId = useAuthStore((state) => state.claims?.sub);
  return <main className="mx-auto w-full max-w-small space-y-8 px-6 py-10 md:px-8 md:py-14">
    <header className="space-y-3">
      <Link href="/cart" className="focus-ring inline-flex min-h-11 items-center gap-2 rounded-lg text-[14px] text-muted-foreground"><ArrowLeft className="size-4" aria-hidden />Về giỏ hàng</Link>
      <h1 className="font-serif text-headline-2 font-normal">Đặt hàng</h1>
      <p className="text-[15px] text-muted-foreground">Chọn nơi nhận hàng, kiểm tra báo giá và tiếp tục thanh toán qua Stripe.</p>
    </header>
    <CheckoutContents key={accountId} initialToken={initialToken} />
  </main>;
}

function CheckoutContents({ initialToken }: { initialToken: string | null }) {
  const router = useRouter();
  const checkout = useCheckout(initialToken, (token) => router.replace(token ? `/checkout?submission=${token}` : "/checkout", { scroll: false }));
  const addresses = useAddresses();
  const [selected, setSelected] = useState<string | null>(null);
  const [promotionId, setPromotionId] = useState<string | null>(null);
  const addressId = selected ?? addresses.data?.find((address) => address.is_default)?.id ?? addresses.data?.[0]?.id;
  const autoStarted = useRef(false);
  const { quote, preview, token, submission, submit, recover } = checkout;
  const [now, setNow] = useState(() => Date.now());
  const uncertainPreview = quote.isError && isRetryable(quote.error);
  const expired = preview ? now >= Date.parse(preview.expires_at) : false;
  const vouchers = useCheckoutVouchers(preview?.checkout_token ?? "", Boolean(preview && !expired && !token && !quote.isPending && !uncertainPreview), changeVoucher);
  const optionsProblem = checkoutProblem(vouchers.options.error);
  const invalidOptions = optionsProblem && optionsProblem.action !== "RETRY";
  const error = token ? submission.error ?? (submission.data ? null : submit.error) : quote.error ?? (invalidOptions ? vouchers.options.error : null);
  const problem = checkoutProblem(error);

  useEffect(() => {
    if (token || optionsProblem?.action !== "VIEW_SUBMISSION") return;
    const recoveryToken = submissionTokenFromUrl(optionsProblem.status_url);
    if (recoveryToken) recover(recoveryToken);
  }, [token, optionsProblem?.action, optionsProblem?.status_url, recover]);

  useEffect(() => {
    const timer = window.setInterval(() => setNow(Date.now()), 1_000);
    return () => window.clearInterval(timer);
  }, []);

  const createQuote = quote.mutate;
  useEffect(() => {
    if (token || autoStarted.current || !addressId || addresses.isError || addresses.isPending) return;
    autoStarted.current = true;
    createQuote({ delivery_address_id: addressId });
  }, [token, addressId, addresses.isError, addresses.isPending, createQuote]);

  const response = submit.data?.data && isSubmissionReady(submit.data.data) ? submit.data.data : submission.data?.data;
  const destination = response && isSubmissionReady(response) ? submissionDestination(response) : null;
  useEffect(() => { if (destination) router.replace(destination); }, [destination, router]);

  function freshQuote() {
    if (!addressId) return;
    const nextPromotion = problem?.action === "CHANGE_VOUCHER" ? null : promotionId;
    setPromotionId(nextPromotion);
    autoStarted.current = true;
    checkout.startFresh();
    quote.mutate({ delivery_address_id: addressId, ...(nextPromotion ? { promotion_id: nextPromotion } : {}) });
  }

  function changeVoucher(nextPromotion: string | null) {
    if (!addressId || !preview || expired || quote.isPending || uncertainPreview || token) return;
    setPromotionId(nextPromotion);
    quote.startNewIntent();
    quote.mutate({ delivery_address_id: addressId, previous_checkout_token: preview.checkout_token, promotion_id: nextPromotion });
  }

  const freshAllowed = problem?.action === "START_NEW_PREVIEW" || problem?.action === "CHANGE_VOUCHER";
  if (token) return <Card><CardContent className="space-y-6 py-8" aria-live="polite">
    <div className="flex items-start gap-4"><Clock3 className="mt-1 size-6 shrink-0 text-info" aria-hidden /><div className="space-y-2">
      <h2 className="font-serif text-headline-4 font-normal">{destination ? "Đơn hàng đã sẵn sàng" : "Đang kiểm tra kết quả đặt hàng"}</h2>
      <p className="text-[15px] text-muted-foreground">{destination ? "Đang chuyển tới đơn hàng của bạn…" : "Yêu cầu có thể đã được nhận. Hãy giữ trang này hoặc quay lại để xem kết quả; không đặt thêm đơn trong lúc chờ."}</p>
      {response && !isSubmissionReady(response) && response.state === "RESULT_UNKNOWN" && <p className="text-caption text-muted-foreground">Chưa xác định được kết quả. Hệ thống sẽ tiếp tục kiểm tra cùng yêu cầu.</p>}
    </div></div>
    {error != null && <ErrorState error={error} />}
    {problem?.promotion_reason && <Alert variant="warning">{voucherReason(problem.promotion_reason)}</Alert>}
    {problem?.dropped_promotion && <Alert variant="warning">Voucher đã bị bỏ khỏi đơn: {voucherReason(problem.dropped_promotion.reason)} Hãy xem báo giá mới trước khi đặt hàng.</Alert>}
    {problem?.action === "EDIT_CART" && <Link href="/cart" className="focus-ring inline-flex min-h-11 items-center text-clay-strong">Sửa giỏ hàng</Link>}
    {freshAllowed && <Button variant="outline" onClick={freshQuote} disabled={!addressId || addresses.isError}>Xem báo giá mới</Button>}
    {problemCode(submission.error) === "SUBMISSION_NOT_FOUND" && <Button variant="outline" disabled={submit.isPending} onClick={() => submit.mutate(token)}>Gửi lại cùng yêu cầu đặt hàng</Button>}
    {(checkout.pollPaused || (submission.isError && isRetryable(submission.error))) && <StatusRetry key={submission.errorUpdatedAt} error={submission.error} busy={submission.isFetching || submit.isPending} onRetry={checkout.resume} />}
    {checkout.pollPaused && !destination && <p className="text-caption text-muted-foreground">Chưa có kết quả cuối. Bạn có thể tiếp tục kiểm tra; yêu cầu cũ vẫn được giữ để phục hồi khi tải lại trang.</p>}
    {addresses.isError && freshAllowed && <ErrorState error={addresses.error} onRetry={() => void addresses.refetch()} />}
  </CardContent></Card>;

  if (addresses.isPending) return <CheckoutLoading />;
  if (addresses.isError) return <ErrorState error={addresses.error} onRetry={() => void addresses.refetch()} />;
  if (!addresses.data?.length) return <EmptyState icon={MapPin} title="Chưa có địa chỉ nhận hàng" description="Thêm địa chỉ vào sổ địa chỉ, rồi quay lại đặt hàng." action={<Link href="/profile/addresses" className="focus-ring inline-flex min-h-11 items-center text-clay-strong">Thêm địa chỉ nhận hàng</Link>} />;

  const locked = quote.isPending || uncertainPreview || vouchers.busy;
  const canSubmit = preview && !expired && !quote.isError && !invalidOptions && !locked && preview.address.address_id === addressId && preview.promotion.promotion_id === promotionId;

  return <div className="space-y-6">
    {error != null && <div className="space-y-3">
      <ErrorState error={error} />
      {problem?.changed_fields?.length ? <Alert variant="warning">Nội dung đơn đã thay đổi. Hãy xem báo giá mới trước khi xác nhận lại.</Alert> : null}
      {problem?.promotion_reason && <Alert variant="warning">{voucherReason(problem.promotion_reason)}</Alert>}
      {problem?.dropped_promotion && <Alert variant="warning">Voucher đã bị bỏ khỏi đơn: {voucherReason(problem.dropped_promotion.reason)} Hãy xem báo giá mới trước khi đặt hàng.</Alert>}
      {problem?.sku_ids?.length || problem?.rejected_lines?.length ? <p className="text-caption text-muted-foreground">Biến thể cần kiểm tra: {(problem.sku_ids ?? problem.rejected_lines?.map((line) => line.sku_id))?.join(", ")}</p> : null}
      {uncertainPreview && <Button variant="outline" disabled={quote.isRetryDelayed} onClick={() => quote.variables && quote.mutate(quote.variables)}>{quote.isRetryDelayed ? `Thử lại sau ${quote.retryDelaySeconds} giây` : "Thử lại cùng báo giá"}</Button>}
      {freshAllowed && <Button variant="outline" disabled={locked} onClick={freshQuote}>Xem báo giá mới</Button>}
      {problem?.action === "EDIT_CART" && <Link href="/cart" className="focus-ring inline-flex min-h-11 items-center text-clay-strong">Sửa giỏ hàng</Link>}
      {problem?.action === "CHANGE_ADDRESS" && <p role="alert" id="checkout-address-error" className="text-[14px] text-danger">Chọn địa chỉ khác hoặc cập nhật sổ địa chỉ, rồi tải lại danh sách.</p>}
    </div>}
    <div className="grid items-start gap-6 lg:grid-cols-[1fr_320px]">
      <div className="min-w-0 space-y-6">
        <Card><CardHeader><CardTitle>Địa chỉ nhận hàng</CardTitle></CardHeader><CardContent className="space-y-4">
          <fieldset disabled={locked} aria-describedby={problem?.action === "CHANGE_ADDRESS" ? "checkout-address-error" : undefined}>
            <legend className="sr-only">Chọn địa chỉ giao hàng</legend>
            <div className="space-y-3">{addresses.data.map((address) => <label key={address.id} className="flex min-h-11 cursor-pointer items-start gap-3 rounded-lg border border-border p-4">
              <input type="radio" name="delivery_address_id" value={address.id} checked={addressId === address.id} className="focus-ring mt-1 size-4 shrink-0 accent-clay" onChange={() => {
                setSelected(address.id);
                quote.startNewIntent();
                quote.mutate({ delivery_address_id: address.id, ...(preview ? { previous_checkout_token: preview.checkout_token } : {}), ...(promotionId ? { promotion_id: promotionId } : {}) });
              }} />
              <span className="min-w-0 space-y-1 text-[14px]"><span className="block font-medium">{address.recipient_name}{address.is_default ? " · Mặc định" : ""}</span><span className="block text-muted-foreground">{address.recipient_phone}</span><span className="block break-words text-muted-foreground">{formatAddressLine(address)}</span></span>
            </label>)}</div>
          </fieldset>
          <div className="flex flex-wrap items-center gap-4"><Link href="/profile/addresses" className="focus-ring inline-flex min-h-11 items-center text-[14px] text-clay-strong">Quản lý địa chỉ</Link><Button variant="ghost" disabled={locked || addresses.isFetching} onClick={() => void addresses.refetch()}>Tải lại địa chỉ</Button></div>
        </CardContent></Card>
        {preview && <CheckoutVoucherTray vouchers={vouchers} disabled={quote.isPending || uncertainPreview} expired={expired} appliedId={preview.promotion.promotion_id} now={now} onApply={changeVoucher} onRemove={() => changeVoucher(null)} />}
        {quote.isPending ? <QuoteLoading /> : preview ? <Card><CardHeader><CardTitle>Sản phẩm trong đơn</CardTitle></CardHeader><CardContent><ul className="divide-y divide-border">{preview.items.map((item) => <li key={item.line_reference} className="flex gap-4 py-5 first:pt-0 last:pb-0">
          <div className="size-20 shrink-0 overflow-hidden rounded-lg bg-surface-2"><StorefrontImage src={item.image_url} alt={item.product_name} className="size-full object-cover" /></div>
          <div className="min-w-0 flex-1 space-y-2"><p className="break-words text-[15px] font-medium">{item.product_name}</p><p className="text-caption text-muted-foreground">{Object.values(item.variant_attributes).join(" · ")}</p><p className="text-[14px]">{item.quantity} × {formatVND(item.unit_price.amount_minor)} {item.list_unit_price.amount_minor > item.unit_price.amount_minor && <del className="text-caption text-muted-foreground">{formatVND(item.list_unit_price.amount_minor)}</del>}</p><p className="text-[15px] font-medium tabular-nums">{formatVND(item.line_total.amount_minor)}</p></div>
        </li>)}</ul></CardContent></Card> : null}
      </div>
      <Card className="lg:sticky lg:top-28"><CardHeader><CardTitle>Xác nhận đơn hàng</CardTitle></CardHeader><CardContent className="space-y-5">
        {preview && !quote.isPending ? <>
          <dl className="space-y-3 text-[15px]"><div className="flex justify-between gap-3"><dt>Tiền sản phẩm</dt><dd className="tabular-nums">{formatVND(preview.pricing.total_amount.amount_minor)}</dd></div><div className="flex justify-between gap-3"><dt>Giảm giá</dt><dd className="tabular-nums">{formatVND(preview.pricing.total_discount.amount_minor)}</dd></div><div className="flex flex-wrap justify-between gap-3 border-t border-border pt-4"><dt>Tổng thanh toán</dt><dd className="font-serif text-headline-4 font-normal tabular-nums">{formatVND(preview.pricing.final_amount.amount_minor)}</dd></div></dl>
          <p className="text-caption text-muted-foreground">Báo giá có hiệu lực đến {formatDateTime(preview.expires_at)}. Báo giá này chưa giữ hàng.</p>
          {expired && <Alert variant="warning">Báo giá đã hết hạn. Tải báo giá mới trước khi đặt hàng.</Alert>}
        </> : <><Skeleton className="h-5 w-full" /><Skeleton className="h-5 w-full" /><Skeleton className="h-9 w-3/4" /></>}
        <p className="text-caption text-muted-foreground">Không tính phí vận chuyển trong phiên bản hiện tại. Thanh toán bằng Stripe.</p>
        <Button variant="clay" className="w-full" disabled={!canSubmit} onClick={checkout.placeOrder}><CreditCard aria-hidden />Đặt hàng và thanh toán</Button>
        <Button variant="outline" className="w-full" disabled={locked} onClick={freshQuote}>{quote.isPending ? <LoaderCircle className="animate-spin" aria-hidden /> : null}Tải báo giá mới</Button>
      </CardContent></Card>
    </div>
  </div>;
}

function QuoteLoading() {
  return <div className="space-y-5 rounded-xl border border-border bg-surface-overlay p-6" aria-busy="true" aria-label="Đang tải báo giá"><Skeleton className="h-7 w-48" />{[0, 1].map((index) => <div key={index} className="flex gap-4"><Skeleton className="size-20 shrink-0 rounded-lg" /><div className="w-full space-y-3"><Skeleton className="h-5 w-3/4" /><Skeleton className="h-4 w-1/2" /><Skeleton className="h-5 w-1/3" /></div></div>)}</div>;
}

function StatusRetry({ error, busy, onRetry }: { error: unknown; busy: boolean; onRetry: () => void }) {
  const [deadline] = useState(() => Date.now() + (retryAfterSeconds(error) ?? 0) * 1_000);
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    const timer = window.setInterval(() => setNow(Date.now()), 250);
    return () => window.clearInterval(timer);
  }, []);
  const cooldown = Math.max(0, Math.ceil((deadline - now) / 1_000));
  return <Button variant="outline" disabled={busy || cooldown > 0} onClick={onRetry}>{cooldown > 0 ? `Thử lại sau ${cooldown} giây` : "Kiểm tra lại kết quả"}</Button>;
}

export function CheckoutLoading() {
  return <div className="grid gap-6 lg:grid-cols-[1fr_320px]" aria-busy="true" aria-label="Đang tải đặt hàng"><div className="space-y-6"><Skeleton className="h-64 rounded-xl" /><QuoteLoading /></div><Skeleton className="h-80 rounded-xl" /></div>;
}
