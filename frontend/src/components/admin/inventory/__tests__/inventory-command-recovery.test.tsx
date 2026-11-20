// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { StockReceiptForm } from "@/components/admin/inventory/stock-receipt-form";
import { StockAdjustmentForm } from "@/components/admin/inventory/stock-adjustment-form";
import { WarehouseDetail } from "@/components/admin/inventory/warehouse-detail";
import { CommandDetail } from "@/components/admin/inventory/command-detail";
import { inventoryAdminApi, type AdminCommandOutcome, type StockMutationResponse, type Warehouse } from "@/lib/api/inventory-admin";
import { httpError, networkError, problem } from "@/lib/api/__tests__/helpers";

vi.mock("@/lib/api/inventory-admin", () => ({ inventoryAdminApi: {
  recordStockReceipt: vi.fn(), recordStockAdjustment: vi.fn(),
  getWarehouse: vi.fn(), updateWarehouse: vi.fn(), getCommandOutcome: vi.fn(),
} }));
vi.mock("@/components/ui/toast", () => ({ useToast: () => ({ addToast: vi.fn() }) }));
beforeEach(() => vi.resetAllMocks());
afterEach(cleanup);

const success: StockMutationResponse = { command_id: "command-1", correlation_id: "command-1", outcome: "ACCEPTED", operation_id: "operation-1", recorded_at: "2026-10-15T00:00:00Z", replayed: false };
function mount(ui: React.ReactNode) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  return render(<QueryClientProvider client={client}>{ui}</QueryClientProvider>);
}
function fill(label: string, value: string) {
  fireEvent.change(screen.getByLabelText(label), { target: { value } });
}
function setupForm(kind: "receipt" | "adjustment") {
  const fn = vi.mocked(kind === "receipt" ? inventoryAdminApi.recordStockReceipt : inventoryAdminApi.recordStockAdjustment);
  mount(kind === "receipt" ? <StockReceiptForm /> : <StockAdjustmentForm />);
  fill("Mã kho *", "warehouse-1"); fill("Mã SKU *", "sku-1"); fill("Số lượng *", "1");
  if (kind === "receipt") {
    fill("Mã phiếu nhận *", "receipt-1"); fill("Mã dòng phiếu *", "1"); fill("Mã GRN / phiếu giao hàng *", "grn-1");
  } else {
    fill("Mã hồ sơ điều chỉnh *", "adjustment-1"); fill("Mã dòng hồ sơ *", "1");
  }
  fill("Tham chiếu bằng chứng *", "original-evidence");
  const name = kind === "receipt" ? "Ghi nhận nhập hàng" : "Ghi nhận điều chỉnh";
  return { fn, name };
}

describe.each(["receipt", "adjustment"] as const)("Inventory %s recovery", kind => {
  it.each([false, true])("keeps the original body/key after response loss, attempted edit=%s", async edited => {
    const { fn, name } = setupForm(kind);
    fn.mockRejectedValueOnce(networkError()).mockResolvedValueOnce(success);
    fireEvent.click(screen.getByRole("button", { name }));
    await screen.findByRole("button", { name: "Thử lại đúng lệnh" });
    const input = screen.getByLabelText("Tham chiếu bằng chứng *");
    expect(input.matches(":disabled")).toBe(true);
    const lookup = screen.getByRole("link", { name: "Tra cứu kết quả lệnh (tab mới)" });
    expect(lookup.getAttribute("target")).toBe("_blank");
    expect(lookup.getAttribute("href")).toBe(`/admin/inventory/commands/${fn.mock.calls[0][1]}`);
    // Programmatic events can bypass fieldset disabled: the transport must still replay the frozen body.
    if (edited) fill("Tham chiếu bằng chứng *", "edited-evidence");
    fireEvent.click(screen.getByRole("button", { name: "Thử lại đúng lệnh" }));
    await screen.findByText("Đã ghi nhận lệnh");
    expect(fn).toHaveBeenCalledTimes(2);
    expect(fn.mock.calls[1]).toEqual(fn.mock.calls[0]);
    expect(input.matches(":disabled")).toBe(false);
  });

  it("allows a new edited intent after a durable 409 without labeling it a replay", async () => {
    const { fn, name } = setupForm(kind);
    fn.mockRejectedValueOnce(httpError(409, problem("OPERATION_IDENTITY_CONFLICT", 409, { retryable: false }))).mockResolvedValueOnce(success);
    fireEvent.click(screen.getByRole("button", { name }));
    await waitFor(() => expect(fn).toHaveBeenCalledTimes(1));
    await waitFor(() => expect(screen.getByRole("button", { name }).hasAttribute("disabled")).toBe(false));
    expect(screen.queryByRole("button", { name: "Thử lại đúng lệnh" })).toBeNull();
    expect(screen.getByLabelText("Tham chiếu bằng chứng *").matches(":disabled")).toBe(false);
    fill(kind === "receipt" ? "Mã phiếu nhận *" : "Mã hồ sơ điều chỉnh *", "corrected-identity");
    fireEvent.click(screen.getByRole("button", { name }));
    await screen.findByText("Đã ghi nhận lệnh");
    expect(fn.mock.calls[1][1]).not.toBe(fn.mock.calls[0][1]);
    expect(fn.mock.calls[1][0]).not.toEqual(fn.mock.calls[0][0]);
  });

  it("honors Retry-After while preserving the unresolved intent", async () => {
    const { fn, name } = setupForm(kind);
    fn.mockRejectedValueOnce(httpError(503, problem("INVENTORY_BUSY", 503, { retryable: true }), { "retry-after": "1" })).mockResolvedValueOnce(success);
    fireEvent.click(screen.getByRole("button", { name }));
    const waiting = await screen.findByRole("button", { name: "Thử lại sau 1 giây" });
    expect(waiting.hasAttribute("disabled")).toBe(true);
    await waitFor(() => expect(screen.queryByRole("button", { name: "Thử lại đúng lệnh" })).not.toBeNull(), { timeout: 2500 });
    fireEvent.click(screen.getByRole("button", { name: "Thử lại đúng lệnh" }));
    await screen.findByText("Đã ghi nhận lệnh");
    expect(fn.mock.calls[1]).toEqual(fn.mock.calls[0]);
  });
});

it("reloads the latest warehouse fields/revision before allowing a new intent", async () => {
  const original: Warehouse = { warehouse_id: "warehouse-1", name: "Old warehouse", address: { line1: "Old address", locality: "HCM", country_code: "VN" }, is_active: true, operational_state: "NORMAL", fulfillment_priority: 1, revision: 1 };
  const fresh = { ...original, name: "Concurrent name", address: { ...original.address, line1: "New address" }, revision: 2 };
  vi.mocked(inventoryAdminApi.getWarehouse).mockResolvedValueOnce(original).mockResolvedValueOnce(fresh);
  const fn = vi.mocked(inventoryAdminApi.updateWarehouse);
  fn.mockRejectedValueOnce(httpError(409, problem("WAREHOUSE_CONFLICT", 409, { retryable: false }))).mockResolvedValueOnce({ ...success, warehouse: { ...fresh, name: "New intent", revision: 3 } });
  mount(<WarehouseDetail warehouseId="warehouse-1" />);
  await screen.findByDisplayValue("Old warehouse");
  fill("Lý do thay đổi *", "QA conflict"); fill("Tham chiếu bằng chứng *", "QA evidence");
  fireEvent.click(screen.getByRole("button", { name: "Lưu thay đổi" }));
  await screen.findByRole("button", { name: "Tải bản kho mới nhất" });
  expect(screen.getByRole("button", { name: "Lưu thay đổi" }).hasAttribute("disabled")).toBe(true);
  expect(screen.queryByRole("button", { name: "Thử lại đúng lệnh" })).toBeNull();
  fireEvent.click(screen.getByRole("button", { name: "Tải bản kho mới nhất" }));
  await screen.findByDisplayValue("Concurrent name");
  expect((screen.getByLabelText("Địa chỉ dòng 1 *") as HTMLInputElement).value).toBe("New address");
  expect((screen.getByLabelText("Lý do thay đổi *") as HTMLInputElement).value).toBe("");
  fill("Tên kho *", "New intent"); fill("Lý do thay đổi *", "New audited reason"); fill("Tham chiếu bằng chứng *", "New evidence");
  fireEvent.click(screen.getByRole("button", { name: "Lưu thay đổi" }));
  await waitFor(() => expect(fn).toHaveBeenCalledTimes(2));
  expect(fn.mock.calls[1][1].expected_revision).toBe(2);
  expect(fn.mock.calls[1][2]).not.toBe(fn.mock.calls[0][2]);
});

const rejected: AdminCommandOutcome = { command_id: "command-1", correlation_id: "correlation-1", operation: "RECORD_STOCK_ADJUSTMENT", result: "REJECTED", outcome: "REJECTED", http_status: 409, error_code: "INSUFFICIENT_SELLABLE_STOCK", recorded_at: "2026-10-15T00:00:00Z" };
it("shows a rejected command as a rejection with the backend code and localized reason", async () => {
  vi.mocked(inventoryAdminApi.getCommandOutcome).mockResolvedValue(rejected);
  mount(<CommandDetail commandId="command-1" />);
  const badge = await screen.findByText("Đã từ chối");
  expect(badge.className).toContain("text-danger");
  expect(badge.className).not.toContain("text-success");
  expect(screen.queryByText("INSUFFICIENT_SELLABLE_STOCK")).not.toBeNull();
  expect(screen.queryByText("409")).not.toBeNull();
  expect(screen.queryByText("Không đủ tồn bán được cho thao tác này. Hãy tải lại vị trí tồn.")).not.toBeNull();
});
it("retains the accepted command display without inventing a rejection", async () => {
  vi.mocked(inventoryAdminApi.getCommandOutcome).mockResolvedValue({ ...rejected, result: "ACCEPTED", outcome: "ACCEPTED", http_status: 200, error_code: undefined });
  mount(<CommandDetail commandId="command-1" />);
  expect((await screen.findByText("Đã chấp nhận")).className).toContain("text-success");
  expect(screen.queryByText("Nguyên nhân")).toBeNull();
});
it("keeps a useful rejection message when an old receipt has no error code", async () => {
  vi.mocked(inventoryAdminApi.getCommandOutcome).mockResolvedValue({ ...rejected, error_code: undefined, http_status: undefined });
  mount(<CommandDetail commandId="command-1" />);
  await screen.findByText("Đã từ chối");
  expect(screen.queryByText("Nguyên nhân")).not.toBeNull();
  expect(screen.queryByText("Mã lỗi")).toBeNull();
});
