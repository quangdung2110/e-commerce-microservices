// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import {
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
} from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { ToastProvider } from "@/components/ui/toast";
import { ReturnCommandForm } from "@/components/admin/inventory/return-command-form";
import { AccountDeletion } from "@/components/admin/system/account-deletion";
import { ModerationQueue } from "@/components/admin/reviews/moderation-queue";
import { adminSystemApi } from "@/lib/api/admin-system";
import { adminReviewApi, type ModerationCase } from "@/lib/api/admin-review";
import { inventoryAdminApi } from "@/lib/api/inventory-admin";
import { httpError, networkError } from "@/lib/api/__tests__/helpers";

vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace: vi.fn() }),
  useSearchParams: () => new URLSearchParams("status=HIDDEN_BY_REPORT"),
}));
vi.mock("@/lib/api/inventory-admin", () => ({
  inventoryAdminApi: { recordInspection: vi.fn() },
}));
vi.mock("@/lib/api/admin-system", () => ({
  adminSystemApi: { deleteAccount: vi.fn() },
}));
vi.mock("@/lib/api/admin-review", () => ({
  adminReviewApi: {
    list: vi.fn(),
    dismiss: vi.fn(),
    reply: vi.fn(),
    takedown: vi.fn(),
  },
}));

function mount(ui: React.ReactNode) {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  return render(
    <QueryClientProvider client={client}>
      <ToastProvider>{ui}</ToastProvider>
    </QueryClientProvider>,
  );
}
beforeEach(() => vi.clearAllMocks());
afterEach(cleanup);
describe("step 12 operational forms", () => {
  it("freezes a failed Inventory payload and retries HTTP 500 with the same command ID", async () => {
    vi.mocked(inventoryAdminApi.recordInspection)
      .mockRejectedValueOnce(httpError(500))
      .mockResolvedValueOnce({
        command_id: "command",
        correlation_id: "command",
        operation_id: "operation",
        movement_id: "movement",
        outcome: "ACCEPTED",
        recorded_at: "2026-10-10T00:00:00Z",
        replayed: true,
      });
    mount(
      <ReturnCommandForm
        action={{
          kind: "inspection",
          warehouse_id: "w",
          return_receipt_id: "r",
          return_receipt_line_id: "l",
          max: 3,
        }}
      />,
    );
    fireEvent.change(screen.getByLabelText("Mã kiểm định *"), {
      target: { value: "inspection" },
    });
    fireEvent.change(screen.getByLabelText("Tham chiếu bảng kiểm *"), {
      target: { value: "checklist" },
    });
    fireEvent.change(screen.getByLabelText("Tham chiếu bằng chứng *"), {
      target: { value: "evidence" },
    });
    fireEvent.click(screen.getByRole("checkbox"));
    fireEvent.submit(
      screen
        .getByRole("button", { name: "Xác nhận ghi nhận" })
        .closest("form")!,
    );
    await screen.findByText("Kết quả lệnh chưa rõ");
    expect(
      (screen.getByLabelText("Mã kiểm định *") as HTMLInputElement).disabled,
    ).toBe(true);
    fireEvent.submit(
      screen
        .getByRole("button", { name: "Thử lại đúng lệnh" })
        .closest("form")!,
    );
    await waitFor(() =>
      expect(inventoryAdminApi.recordInspection).toHaveBeenCalledTimes(2),
    );
    const [first, second] = vi.mocked(inventoryAdminApi.recordInspection).mock
      .calls;
    expect(first).toEqual(second);
    expect(first[0]).toMatchObject({
      result: "PASS",
      warehouse_id: "w",
      return_receipt_id: "r",
      return_receipt_line_id: "l",
    });
    expect(first[0]).not.toHaveProperty("fail_reason");
  });
  it("requires exact account confirmation, and never resends an ambiguous deletion", async () => {
    vi.mocked(adminSystemApi.deleteAccount).mockRejectedValue(networkError());
    mount(<AccountDeletion />);
    const id = "0199cafe-0000-7000-8000-000000000001";
    fireEvent.change(screen.getByLabelText("Mã tài khoản (UUID) *"), {
      target: { value: id },
    });
    fireEvent.click(
      screen.getByRole("button", { name: "Yêu cầu xoá tài khoản" }),
    );
    const confirm = screen.getByRole("button", {
      name: "Xoá tài khoản",
    });
    expect((confirm as HTMLButtonElement).disabled).toBe(true);
    fireEvent.change(screen.getByLabelText(`Gõ ${id} để xác nhận`), {
      target: { value: id },
    });
    fireEvent.click(confirm);
    await screen.findByText("Chưa xác định kết quả xoá");
    expect(adminSystemApi.deleteAccount).toHaveBeenCalledTimes(1);
    expect(
      (
        screen.getByRole("button", {
          name: "Yêu cầu xoá tài khoản",
        }) as HTMLButtonElement
      ).disabled,
    ).toBe(true);
    expect(
      (screen.getByLabelText("Mã tài khoản (UUID) *") as HTMLInputElement)
        .disabled,
    ).toBe(true);
  });
  it("loads a hidden moderation case from the queue, disables reply and dismisses by review ID", async () => {
    const reviewId = "0199cafe-0000-7000-8000-000000000001";
    const item: ModerationCase = {
      review: {
        review_id: reviewId,
        product_id: "1",
        sku_id: "2",
        rating: 4,
        content: "Nội dung đánh giá bị báo cáo",
        has_media: false,
        media: [],
        visibility_status: "HIDDEN_BY_REPORT",
        active_report_count: 3,
        hidden_at: "2026-10-10T00:00:00Z",
        first_reported_at: "2026-10-10T00:00:00Z",
        created_at: "2026-10-09T00:00:00Z",
      },
      reports: [
        {
          report_id: reviewId,
          reason: "OFFENSIVE",
          detail: "Có nội dung xúc phạm",
          status: "ACTIONED",
          created_at: "2026-10-10T00:00:00Z",
        },
      ],
      first_reported_at: "2026-10-10T00:00:00Z",
      hidden_at: "2026-10-10T00:00:00Z",
    };
    vi.mocked(adminReviewApi.list).mockResolvedValue({
      items: [item],
      next_cursor: null,
    });
    vi.mocked(adminReviewApi.dismiss).mockResolvedValue({
      review_id: reviewId,
      visibility_status: "APPROVED",
      active_report_count: 0,
      dismissed_report_count: 3,
    });
    mount(<ModerationQueue />);
    const buttons = await screen.findAllByRole("button", {
      name: "Xem và phân xử",
    });
    fireEvent.click(buttons[0]);
    expect(
      (
        (await screen.findByRole("button", {
          name: "Phản hồi chính thức",
        })) as HTMLButtonElement
      ).disabled,
    ).toBe(true);
    expect(screen.getByText("Có nội dung xúc phạm")).toBeTruthy();
    fireEvent.click(screen.getByRole("button", { name: "Gỡ toàn bộ báo cáo" }));
    fireEvent.submit(
      screen
        .getByRole("button", { name: "Xác nhận" })
        .closest("form")!,
    );
    await waitFor(() =>
      expect(adminReviewApi.dismiss).toHaveBeenCalledWith(reviewId),
    );
    expect(adminReviewApi.reply).not.toHaveBeenCalled();
  });
});
