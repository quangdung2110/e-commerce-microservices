// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { InventoryOperations } from "@/components/admin/inventory/inventory-operations";
import { ReturnLookup } from "@/components/admin/inventory/return-lookup";
import { inventoryAdminApi } from "@/lib/api/inventory-admin";

let search = "";
vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace: vi.fn(), push: vi.fn() }),
  useSearchParams: () => new URLSearchParams(search),
}));
vi.mock("@/lib/api/inventory-admin", () => ({
  inventoryAdminApi: {
    listWarehouses: vi.fn(),
    listStockPositions: vi.fn(),
    listStockMovements: vi.fn(),
    listDispatchQueue: vi.fn(),
    listInspectionQueue: vi.fn(),
    listQuarantineItems: vi.fn(),
    listRefundScopes: vi.fn(),
    findRefundScopeByOrder: vi.fn(),
  },
}));

const empty = { items: [], next_cursor: null };

function mount(ui: React.ReactNode) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(<QueryClientProvider client={client}>{ui}</QueryClientProvider>);
}

beforeEach(() => {
  vi.clearAllMocks();
  search = "";
  for (const fn of Object.values(inventoryAdminApi)) {
    vi.mocked(fn as never as () => Promise<unknown>).mockResolvedValue(empty);
  }
});
afterEach(cleanup);

describe("Inventory admin lists load without any filter", () => {
  it.each([
    ["positions", "listStockPositions"],
    ["movements", "listStockMovements"],
    ["dispatch", "listDispatchQueue"],
    ["inspection", "listInspectionQueue"],
    ["quarantine", "listQuarantineItems"],
  ] as const)("%s fetches immediately", async (view, method) => {
    search = `view=${view}`;
    mount(<InventoryOperations />);
    await waitFor(() => expect(inventoryAdminApi[method]).toHaveBeenCalledTimes(1));
    const query = vi.mocked(inventoryAdminApi[method]).mock.calls[0][0] as Record<string, unknown>;
    expect(query.warehouse_id).toBeUndefined();
    expect(query.sku_id).toBeUndefined();
    expect(screen.queryByText("Chọn phạm vi tra cứu")).toBeNull();
  });

  it("shows received stock as the receipt movements and returned goods as return receipts", async () => {
    search = "view=receipts";
    mount(<InventoryOperations />);
    await waitFor(() =>
      expect(inventoryAdminApi.listStockMovements).toHaveBeenCalledWith(
        expect.objectContaining({ source_operation_type: "SELLABLE_RECEIPT" }),
        expect.anything(),
      ),
    );
    cleanup();
    vi.clearAllMocks();
    search = "view=returned";
    mount(<InventoryOperations />);
    await waitFor(() =>
      expect(inventoryAdminApi.listStockMovements).toHaveBeenCalledWith(
        expect.objectContaining({ source_operation_type: "RETURN_RECEIPT" }),
        expect.anything(),
      ),
    );
  });

  it("switches tab through history.replaceState so a URL opened with ?view= still changes", async () => {
    search = "view=warehouses";
    const replace = vi.spyOn(window.history, "replaceState");
    mount(<InventoryOperations />);
    fireEvent.click(await screen.findByRole("button", { name: "Tồn kho" }));
    expect(replace).toHaveBeenCalledWith(null, "", "/admin/inventory/operations?view=positions");
    replace.mockRestore();
  });

  it("lists every return request until an order or state narrows it", async () => {
    vi.mocked(inventoryAdminApi.listRefundScopes).mockResolvedValue({
      items: [
        {
          refund_scope_id: "scope-1",
          refund_request_id: "request-1",
          order_id: "order-1",
          state: "IN_PROGRESS",
          revision: 2,
          registered_at: "2026-10-12T01:00:00Z",
        },
      ],
      next_cursor: null,
    });
    mount(<ReturnLookup />);
    expect(await screen.findAllByText("Đang nhận và kiểm định")).not.toHaveLength(0);
    expect(inventoryAdminApi.listRefundScopes).toHaveBeenCalledWith(
      expect.objectContaining({ state: undefined }),
      expect.anything(),
    );
    expect(inventoryAdminApi.findRefundScopeByOrder).not.toHaveBeenCalled();
  });
});
