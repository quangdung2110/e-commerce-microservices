"use client";

import { useEffect, useRef, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { useCommand } from "@/hooks/use-command";
import { checkoutApi, type VoucherOption } from "@/lib/api/checkout";
import { promotionApi } from "@/lib/api/promotion";
import { PollTimeoutError, pollUntil } from "@/lib/api/operation";
import { isRetryable } from "@/lib/api/problem";
import { isClaimCommitted } from "@/lib/checkout-voucher";
import { useAuthStore } from "@/store/useAuthStore";

export function useCheckoutVouchers(token: string, enabled: boolean, onApply: (id: string) => void) {
  const accountId = useAuthStore((state) => state.claims?.sub);
  const controller = useRef<AbortController | null>(null);
  const [claimRetryAt, setClaimRetryAt] = useState(0);
  useEffect(() => () => controller.current?.abort(), []);

  const options = useQuery({
    queryKey: ["checkout", accountId, "voucher-options", token],
    queryFn: ({ signal }) => checkoutApi.voucherOptions(token, signal),
    enabled: Boolean(accountId) && enabled,
    retry: false,
    gcTime: 0,
    staleTime: 0,
    refetchOnWindowFocus: false,
  });

  const acquire = useCommand<void, VoucherOption>({
    retry: false,
    mutationFn: async (option) => {
      controller.current?.abort();
      controller.current = new AbortController();
      const signal = controller.current.signal;
      if (option.required_action === "SAVE") {
        await promotionApi.saveVoucher(option.promotion_id, signal);
      } else if (option.required_action === "CLAIM") {
        const input = { promotion_id: option.promotion_id, expected_campaign_revision: option.expected_campaign_revision };
        const deadline = Date.now() + 30_000;
        // pollUntil waits Retry-After after each 202, including the first response.
        await pollUntil({
          read: async (pollSignal) => {
            // Do not send an early replay when Retry-After exceeds the bounded wait.
            if (Date.now() >= deadline) throw new PollTimeoutError(undefined);
            const result = await promotionApi.claimVoucher(input, pollSignal);
            if (!isClaimCommitted(result.data)) setClaimRetryAt(Date.now() + (result.retryAfterSeconds ?? 1) * 1_000);
            return result;
          },
          isDone: isClaimCommitted,
          signal,
          timeoutMs: 30_000,
        });
      }
      if (signal.aborted) return;
    },
    onSuccess: (_, option) => {
      if (controller.current?.signal.aborted) return;
      // Re-read ownership/expiry even if the next quotation fails. Never use save evaluation for prices.
      void options.refetch();
      onApply(option.promotion_id);
    },
  });

  const unresolved = acquire.isError && (acquire.error instanceof PollTimeoutError || isRetryable(acquire.error));
  return { options, acquire, busy: acquire.isPending || unresolved, unresolved, claimRetryAt };
}
