// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";

import { DeliveryConfirmationDialog } from "@/components/admin/orders/delivery-confirmation-dialog";
import { ToastProvider } from "@/components/ui/toast";
import { httpError, problem } from "@/lib/api/__tests__/helpers";
import { orderApi, type OrderLine } from "@/lib/api/order";

vi.mock("@/lib/api/order", () => ({
  orderApi: { confirmDelivery: vi.fn() },
}));

function line(id: string, name: string): OrderLine {
  return {
    order_line_id: id,
    product_id: "p",
    sku_id: `sku-${id}`,
    quantity: 2,
    unit_price: { amount_minor: 100_000, currency: "VND" },
    fulfillment_status: "DISPATCHED",
    snapshot_name: name,
    snapshot_image_url: null,
    snapshot_attributes: {},
  };
}

function mount(onOpenChange = vi.fn()) {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  render(
    <QueryClientProvider client={client}>
      <ToastProvider>
        <DeliveryConfirmationDialog
          orderId="770004"
          lines={[line("11", "Áo thun"), line("12", "Giày chạy bộ")]}
          open
          onOpenChange={onOpenChange}
        />
      </ToastProvider>
    </QueryClientProvider>,
  );
  return onOpenChange;
}

beforeEach(() => vi.clearAllMocks());
afterEach(cleanup);

describe("DeliveryConfirmationDialog", () => {
  it("không cho gửi khi chưa nhập mã vận đơn hoặc bỏ chọn hết dòng", () => {
    mount();
    const submit = screen.getByRole("button", { name: "Xác nhận đã giao" }) as HTMLButtonElement;
    expect(submit.disabled).toBe(true);

    fireEvent.change(screen.getByLabelText("Mã vận đơn / biên nhận *"), { target: { value: "VN123" } });
    expect(submit.disabled).toBe(false);

    for (const box of screen.getAllByRole("checkbox")) fireEvent.click(box);
    expect(submit.disabled).toBe(true);
  });

  it("gửi các dòng được chọn cùng một mã tham chiếu đã cắt khoảng trắng rồi đóng dialog", async () => {
    vi.mocked(orderApi.confirmDelivery).mockResolvedValue({
      order_id: "770004",
      order_status: "PROCESSING",
      order_completed: false,
      lines: [],
    });
    const onOpenChange = mount();
    fireEvent.change(screen.getByLabelText("Mã vận đơn / biên nhận *"), { target: { value: "  VN123  " } });
    fireEvent.click(screen.getAllByRole("checkbox")[1]);
    fireEvent.submit(screen.getByRole("button", { name: "Xác nhận đã giao" }).closest("form")!);

    await waitFor(() => expect(orderApi.confirmDelivery).toHaveBeenCalledTimes(1));
    const [orderId, body] = vi.mocked(orderApi.confirmDelivery).mock.calls[0];
    expect(orderId).toBe("770004");
    expect(body.lines).toHaveLength(1);
    expect(body.lines[0]).toMatchObject({ order_line_id: "11", delivery_reference: "VN123" });
    expect(Number.isNaN(Date.parse(body.lines[0].delivered_at))).toBe(false);
    await waitFor(() => expect(onOpenChange).toHaveBeenCalledWith(false));
  });

  it("giữ dialog mở và hiện lỗi tiếng Việt khi Order từ chối", async () => {
    vi.mocked(orderApi.confirmDelivery).mockRejectedValue(
      httpError(409, problem("DELIVERY_REQUIRES_DISPATCH", 409, { retryable: false })),
    );
    const onOpenChange = mount();
    fireEvent.change(screen.getByLabelText("Mã vận đơn / biên nhận *"), { target: { value: "VN123" } });
    fireEvent.submit(screen.getByRole("button", { name: "Xác nhận đã giao" }).closest("form")!);

    await screen.findByText(/chưa được kho ghi nhận xuất kho/);
    expect(onOpenChange).not.toHaveBeenCalledWith(false);
  });
});
