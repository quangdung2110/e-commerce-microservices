"use client";

import { useEffect, useRef, useState } from "react";
import { useMutation, useQuery } from "@tanstack/react-query";
import { useCommand } from "@/hooks/use-command";
import { checkoutApi, type CheckoutPreview, type CheckoutPreviewRequest } from "@/lib/api/checkout";
import { checkoutProblem, isSubmissionReady, submissionTokenFromUrl } from "@/lib/checkout";
import { isRetryable, retryAfterSeconds } from "@/lib/api/problem";
import { useAuthStore } from "@/store/useAuthStore";

/** Account-keyed, memory-only quote. The URL retains only the recovery token, never Stripe secrets. */
export function useCheckout(initialToken: string | null, onRecover: (token: string | null) => void) {
  const accountId = useAuthStore((state) => state.claims?.sub);
  const [preview, setPreview] = useState<CheckoutPreview | null>(null);
  const [token, setToken] = useState(initialToken);
  const [pollDeadline, setPollDeadline] = useState(() => Date.now() + 90_000);
  const [pollPaused, setPollPaused] = useState(false);
  const controller = useRef<AbortController | null>(null);
  useEffect(() => () => controller.current?.abort(), []);

  function recover(nextToken: string) {
    setToken(nextToken);
    setPollDeadline(Date.now() + 90_000);
    setPollPaused(false);
    onRecover(nextToken);
  }

  const quote = useCommand<CheckoutPreview, CheckoutPreviewRequest>({
    mutationFn: (body, { commandId }) => {
      controller.current?.abort();
      controller.current = new AbortController();
      return checkoutApi.preview(body, commandId, controller.current.signal);
    },
    onSuccess: (value) => setPreview(value),
    onError: (error) => {
      const problem = checkoutProblem(error);
      if (problem?.action === "VIEW_SUBMISSION") {
        const pendingToken = submissionTokenFromUrl(problem.status_url);
        if (pendingToken) recover(pendingToken);
      }
    },
  });

  const submit = useMutation({
    mutationFn: checkoutApi.submit,
    retry: false,
  });

  const submission = useQuery({
    queryKey: ["checkout", accountId, "submission", token],
    queryFn: ({ signal }) => checkoutApi.submission(token!, signal),
    enabled: Boolean(accountId && token) && !submit.isPending && (!submit.isError || isRetryable(submit.error)),
    retry: false,
    gcTime: 0,
    staleTime: 0,
    refetchOnWindowFocus: false,
    refetchInterval: (query) => {
      if (pollPaused || Date.now() >= pollDeadline) return false;
      if (query.state.data && isSubmissionReady(query.state.data.data)) return false;
      if (query.state.error && !isRetryable(query.state.error)) return false;
      return (query.state.error
        ? retryAfterSeconds(query.state.error) ?? 3
        : query.state.data?.retryAfterSeconds ?? 2) * 1_000;
    },
  });

  // A bounded wait never turns an unknown result into permission to create a new order.
  useEffect(() => {
    if (!token) return;
    const timer = window.setTimeout(() => setPollPaused(true), Math.max(0, pollDeadline - Date.now()));
    return () => window.clearTimeout(timer);
  }, [token, pollDeadline]);

  function placeOrder() {
    if (!preview || token || quote.isPending || submit.isPending) return;
    recover(preview.checkout_token); // Retain recovery BEFORE the POST can lose its response.
    submit.mutate(preview.checkout_token);
  }

  function startFresh() {
    setToken(null);
    setPreview(null);
    onRecover(null);
    quote.reset();
    quote.startNewIntent();
    submit.reset();
  }

  return {
    preview, quote, submit, token, submission, pollPaused, placeOrder, startFresh,
    resume: () => { setPollDeadline(Date.now() + 90_000); setPollPaused(false); void submission.refetch(); },
  };
}
