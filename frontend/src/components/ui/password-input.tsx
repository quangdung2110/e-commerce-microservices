"use client";

import * as React from "react";
import { Eye, EyeOff } from "lucide-react";

import { Input } from "@/components/ui/input";
import { cn } from "@/lib/utils";

/**
 * Ô mật khẩu kèm nút hiện/ẩn.
 *
 * Nút nằm trong ô nên input phải chừa chỗ bên phải; `aria-label` đổi theo trạng
 * thái để trình đọc màn hình biết bấm vào sẽ ra gì.
 */
function PasswordInput({
  className,
  ...props
}: Omit<React.ComponentProps<"input">, "type">) {
  const [visible, setVisible] = React.useState(false);

  return (
    <div className="relative">
      <Input
        type={visible ? "text" : "password"}
        className={cn("pr-11", className)}
        {...props}
      />
      <button
        type="button"
        // Nút này không phải một trạm dừng hữu ích khi tab qua form.
        tabIndex={-1}
        aria-label={visible ? "Ẩn mật khẩu" : "Hiện mật khẩu"}
        onClick={() => setVisible((current) => !current)}
        className="focus-ring absolute inset-y-0 right-0 grid w-11 place-content-center rounded-r-lg text-muted-foreground transition-colors hover:text-foreground"
      >
        {visible ? (
          <EyeOff className="size-4" aria-hidden />
        ) : (
          <Eye className="size-4" aria-hidden />
        )}
      </button>
    </div>
  );
}

export { PasswordInput };
