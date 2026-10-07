"use client";

import * as React from "react";
import { Dialog as SheetPrimitive } from "@base-ui/react/dialog";
import { X } from "lucide-react";

import { cn } from "@/lib/utils";

const Sheet = SheetPrimitive.Root;
const SheetTrigger = SheetPrimitive.Trigger;
const SheetClose = SheetPrimitive.Close;

function SheetContent({
  className,
  children,
  side = "left",
  ...props
}: SheetPrimitive.Popup.Props & { side?: "left" | "right" }) {
  return (
    <SheetPrimitive.Portal>
      <SheetPrimitive.Backdrop
        className={cn(
          "fixed inset-0 z-50 bg-sand-950/40 backdrop-blur-[2px] transition-opacity duration-300",
          "data-starting-style:opacity-0 data-ending-style:opacity-0"
        )}
      />
      <SheetPrimitive.Viewport className="fixed inset-0 z-50">
        <SheetPrimitive.Popup
          data-side={side}
          className={cn(
            "absolute inset-y-0 flex w-[min(88vw,320px)] flex-col overflow-y-auto border-border bg-surface-overlay p-6 shadow-lg",
            "transition-transform duration-300 ease-out-quart",
            "data-[side=left]:left-0 data-[side=left]:border-r data-[side=left]:data-starting-style:-translate-x-full data-[side=left]:data-ending-style:-translate-x-full",
            "data-[side=right]:right-0 data-[side=right]:border-l data-[side=right]:data-starting-style:translate-x-full data-[side=right]:data-ending-style:translate-x-full",
            className
          )}
          {...props}
        >
          {children}
          <SheetPrimitive.Close
            aria-label="Đóng bảng điều hướng"
            className="focus-ring absolute right-3 top-3 grid size-11 place-content-center rounded-lg text-muted-foreground transition-colors duration-200 hover:bg-accent hover:text-foreground"
          >
            <X className="size-5" aria-hidden />
          </SheetPrimitive.Close>
        </SheetPrimitive.Popup>
      </SheetPrimitive.Viewport>
    </SheetPrimitive.Portal>
  );
}

function SheetHeader({ className, ...props }: React.ComponentProps<"div">) {
  return <div className={cn("space-y-2 pr-10", className)} {...props} />;
}

function SheetTitle({ className, ...props }: SheetPrimitive.Title.Props) {
  return (
    <SheetPrimitive.Title
      className={cn("text-headline-5 font-serif font-normal", className)}
      {...props}
    />
  );
}

function SheetDescription({
  className,
  ...props
}: SheetPrimitive.Description.Props) {
  return (
    <SheetPrimitive.Description
      className={cn("text-caption text-muted-foreground", className)}
      {...props}
    />
  );
}

export {
  Sheet,
  SheetClose,
  SheetContent,
  SheetDescription,
  SheetHeader,
  SheetTitle,
  SheetTrigger,
};
