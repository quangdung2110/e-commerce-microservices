"use client";

import { useEffect, useState } from "react";
import { LoaderCircle } from "lucide-react";

import { Alert } from "@/components/ui/alert";
import { ErrorState } from "@/components/ui/error-state";
import {
  PollAbortedError,
  PollTimeoutError,
  pollUntil,
  type PollRead,
} from "@/lib/api/operation";

interface OperationDescription {
  label: string;
  progress?: number;
}

interface OperationStatusProps<T> {
  read: (signal: AbortSignal) => Promise<PollRead<T>>;
  isDone: (data: T) => boolean;
  describe: (data: T | undefined) => OperationDescription;
  onDone?: (data: T) => void;
  timeoutMs?: number;
}

export function OperationStatus<T>({
  read,
  isDone,
  describe,
  onDone,
  timeoutMs,
}: OperationStatusProps<T>) {
  const [data, setData] = useState<T>();
  const [error, setError] = useState<unknown>();
  const [done, setDone] = useState(false);

  useEffect(() => {
    const controller = new AbortController();
    void pollUntil({
      read: async (signal) => {
        const result = await read(signal);
        setData(result.data);
        return result;
      },
      isDone,
      signal: controller.signal,
      timeoutMs,
    })
      .then((result) => {
        setDone(true);
        onDone?.(result);
      })
      .catch((caught) => {
        if (caught instanceof PollAbortedError) return;
        if (caught instanceof PollTimeoutError) {
          setData(caught.lastData);
          return;
        }
        setError(caught);
      });
    return () => controller.abort();
  }, [isDone, onDone, read, timeoutMs]);

  if (error) return <ErrorState error={error} />;

  const state = describe(data);
  const progress =
    state.progress === undefined ? undefined : Math.max(0, Math.min(100, state.progress));

  return (
    <Alert variant={done ? "success" : "info"} icon={done ? undefined : LoaderCircle}>
      <div className="space-y-2">
        <p>{state.label}</p>
        {progress !== undefined ? (
          <div
            role="progressbar"
            aria-label="Tiến độ xử lý"
            aria-valuemin={0}
            aria-valuemax={100}
            aria-valuenow={progress}
            className="h-2 overflow-hidden rounded-full bg-surface-3"
          >
            <div
              className="h-full rounded-full bg-info transition-[width] duration-300"
              style={{ width: `${progress}%` }}
            />
          </div>
        ) : null}
      </div>
    </Alert>
  );
}
