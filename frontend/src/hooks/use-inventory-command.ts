"use client";

import { useRef, useState } from "react";
import { useCommand, type CommandContext } from "@/hooks/use-command";
import { isCommandOutcomeUnknown } from "@/lib/admin-command";
import { isRetryable } from "@/lib/api/problem";

/** An unresolved Inventory command must keep its original body as well as its key. */
export function useInventoryCommand<TData, TInput>(options: {
  mutationFn: (input: TInput, context: CommandContext) => Promise<TData>;
  onSuccess?: (data: TData) => void | Promise<void>;
}) {
  const frozen = useRef<{ input: TInput; context: CommandContext } | null>(null);
  const [commandId, setCommandId] = useState<string>();
  const command = useCommand<TData, TInput>({
    retry: false,
    mutationFn: (input, context) => {
      frozen.current ??= { input: structuredClone(input), context };
      setCommandId(frozen.current.context.commandId);
      return options.mutationFn(frozen.current.input, frozen.current.context);
    },
    onSuccess: async (data) => {
      frozen.current = null;
      await options.onSuccess?.(data);
    },
    onError: (error) => {
      if (!isRetryable(error) && !isCommandOutcomeUnknown(error)) {
        frozen.current = null;
      }
    },
  });
  const isUnresolved = command.isError &&
    (isRetryable(command.error) || isCommandOutcomeUnknown(command.error));

  return {
    ...command,
    commandId,
    isUnresolved,
    inputsLocked: command.isPending || isUnresolved,
    startNewIntent() {
      // The ref also protects the short interval before React renders isPending.
      if (frozen.current) return false;
      command.reset();
      command.startNewIntent();
      setCommandId(undefined);
      return true;
    },
  };
}
