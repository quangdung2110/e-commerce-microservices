import { api } from "@/lib/api/client";
import { inventoryCommandHeaders } from "@/lib/api/command";
import type {
  components,
  operations,
} from "@/lib/api/generated/inventory-admin";

type Schemas = components["schemas"];

export type Warehouse = Schemas["Warehouse"];
export type Address = Schemas["Address"];
export type CreateWarehouseRequest = Schemas["CreateWarehouseRequest"];
export type UpdateWarehouseRequest = Schemas["UpdateWarehouseRequest"];
export type StockReceiptRequest = Schemas["StockReceiptRequest"];
export type StockAdjustmentRequest = Schemas["StockAdjustmentRequest"];
export type MovementReversalRequest = Schemas["MovementReversalRequest"];
export type StockPosition = Schemas["StockPosition"];
export type StockMovement = Schemas["StockMovement"];
export type AdminCommandOutcome = Schemas["AdminCommandOutcome"];
export type StockMutationResponse = Schemas["StockMutationResponse"];
export type WarehouseMutationResponse = Schemas["WarehouseMutationResponse"];
export type PhysicalDispatchRequest = Schemas["PhysicalDispatchRequest"];
export type AdminReservation = Schemas["AdminReservation"];
export type AdminReservationLine = Schemas["AdminReservationLine"];
export type AdminRefundScope = Schemas["AdminRefundScope"];
export type AdminRefundScopeSummary = Schemas["AdminRefundScopeSummary"];
export type AdminRefundScopeLine = Schemas["AdminRefundScopeLine"];
export type InspectionQueueItem = Schemas["InspectionQueueItem"];
export type QuarantineItem = Schemas["QuarantineItem"];
export type ReturnReceiptRequest = Schemas["ReturnReceiptRequest"];
export type InspectionRequest = Schemas["InspectionRequest"];
export type QuarantineDispositionRequest =
  Schemas["QuarantineDispositionRequest"];
export type DispositionReversalRequest = Schemas["DispositionReversalRequest"];
type Query<T extends keyof operations> = NonNullable<
  operations[T]["parameters"]["query"]
>;

const PREFIX = "/api/v1/admin/inventory";

function pathSegment(value: string): string {
  return encodeURIComponent(value);
}

function commandConfig(commandId: string, correlationId?: string) {
  return { headers: inventoryCommandHeaders(commandId, correlationId) };
}

export const inventoryAdminApi = {
  listWarehouses: async (
    params: Query<"listWarehouses">,
    signal?: AbortSignal,
  ) =>
    (
      await api.get<Schemas["WarehousePage"]>(`${PREFIX}/warehouses`, {
        params,
        signal,
      })
    ).data,
  listStockPositions: async (
    params: Query<"listStockPositions">,
    signal?: AbortSignal,
  ) =>
    (
      await api.get<Schemas["StockPositionPage"]>(`${PREFIX}/stock-positions`, {
        params,
        signal,
      })
    ).data,
  listStockMovements: async (
    params: Query<"listStockMovements">,
    signal?: AbortSignal,
  ) =>
    (
      await api.get<Schemas["StockMovementPage"]>(`${PREFIX}/stock-movements`, {
        params,
        signal,
      })
    ).data,
  listDispatchQueue: async (
    params: Query<"listDispatchQueue">,
    signal?: AbortSignal,
  ) =>
    (
      await api.get<Schemas["DispatchQueuePage"]>(`${PREFIX}/dispatch-queue`, {
        params,
        signal,
      })
    ).data,
  listInspectionQueue: async (
    params: Query<"listInspectionQueue">,
    signal?: AbortSignal,
  ) =>
    (
      await api.get<Schemas["InspectionQueuePage"]>(
        `${PREFIX}/inspection-queue`,
        { params, signal },
      )
    ).data,
  listQuarantineItems: async (
    params: Query<"listQuarantineItems">,
    signal?: AbortSignal,
  ) =>
    (
      await api.get<Schemas["QuarantineItemPage"]>(
        `${PREFIX}/quarantine-items`,
        { params, signal },
      )
    ).data,
  listRefundScopes: async (
    params: Query<"listAdminRefundScopes">,
    signal?: AbortSignal,
  ) =>
    (
      await api.get<Schemas["AdminRefundScopeSummaryPage"]>(
        `${PREFIX}/refund-scope-summaries`,
        { params, signal },
      )
    ).data,
  getRefundScope: async (
    id: string,
    signal?: AbortSignal,
  ): Promise<AdminRefundScope> =>
    (
      await api.get<AdminRefundScope>(
        `${PREFIX}/refund-scopes/${pathSegment(id)}`,
        { signal },
      )
    ).data,
  findRefundScopeByOrder: async (
    orderId: string,
    signal?: AbortSignal,
  ): Promise<AdminRefundScope> =>
    (
      await api.get<AdminRefundScope>(`${PREFIX}/refund-scopes`, {
        params: { order_id: orderId },
        signal,
      })
    ).data,
  recordReturnReceipt: async (
    input: ReturnReceiptRequest,
    commandId: string,
  ): Promise<StockMutationResponse> =>
    (
      await api.post<StockMutationResponse>(
        `${PREFIX}/return-receipts`,
        input,
        commandConfig(commandId),
      )
    ).data,
  recordInspection: async (
    input: InspectionRequest,
    commandId: string,
  ): Promise<StockMutationResponse> =>
    (
      await api.post<StockMutationResponse>(
        `${PREFIX}/inspections`,
        input,
        commandConfig(commandId),
      )
    ).data,
  recordQuarantineDisposition: async (
    input: QuarantineDispositionRequest,
    commandId: string,
  ): Promise<StockMutationResponse> =>
    (
      await api.post<StockMutationResponse>(
        `${PREFIX}/quarantine-dispositions`,
        input,
        commandConfig(commandId),
      )
    ).data,
  reverseQuarantineDisposition: async (
    id: string,
    input: DispositionReversalRequest,
    commandId: string,
  ): Promise<StockMutationResponse> =>
    (
      await api.post<StockMutationResponse>(
        `${PREFIX}/quarantine-dispositions/${pathSegment(id)}/reversals`,
        input,
        commandConfig(commandId),
      )
    ).data,
  createWarehouse: async (
    input: CreateWarehouseRequest,
    commandId: string,
  ): Promise<WarehouseMutationResponse> =>
    (
      await api.post<WarehouseMutationResponse>(
        `${PREFIX}/warehouses`,
        input,
        commandConfig(commandId),
      )
    ).data,

  getWarehouse: async (
    warehouseId: string,
    signal?: AbortSignal,
  ): Promise<Warehouse> =>
    (
      await api.get<Warehouse>(
        `${PREFIX}/warehouses/${pathSegment(warehouseId)}`,
        {
          signal,
        },
      )
    ).data,

  updateWarehouse: async (
    warehouseId: string,
    input: UpdateWarehouseRequest,
    commandId: string,
  ): Promise<WarehouseMutationResponse> =>
    (
      await api.patch<WarehouseMutationResponse>(
        `${PREFIX}/warehouses/${pathSegment(warehouseId)}`,
        input,
        commandConfig(commandId),
      )
    ).data,

  recordStockReceipt: async (
    input: StockReceiptRequest,
    commandId: string,
  ): Promise<StockMutationResponse> =>
    (
      await api.post<StockMutationResponse>(
        `${PREFIX}/stock-receipts`,
        input,
        commandConfig(commandId),
      )
    ).data,

  recordStockAdjustment: async (
    input: StockAdjustmentRequest,
    commandId: string,
  ): Promise<StockMutationResponse> =>
    (
      await api.post<StockMutationResponse>(
        `${PREFIX}/stock-adjustments`,
        input,
        commandConfig(commandId),
      )
    ).data,

  reverseStockMovement: async (
    movementId: string,
    input: MovementReversalRequest,
    commandId: string,
  ): Promise<StockMutationResponse> =>
    (
      await api.post<StockMutationResponse>(
        `${PREFIX}/stock-movements/${pathSegment(movementId)}/reversals`,
        input,
        commandConfig(commandId),
      )
    ).data,

  getStockPosition: async (
    warehouseId: string,
    skuId: string,
    signal?: AbortSignal,
  ): Promise<StockPosition> =>
    (
      await api.get<StockPosition>(
        `${PREFIX}/stock-positions/${pathSegment(warehouseId)}/${pathSegment(skuId)}`,
        { signal },
      )
    ).data,

  getStockMovement: async (
    movementId: string,
    signal?: AbortSignal,
  ): Promise<StockMovement> =>
    (
      await api.get<StockMovement>(
        `${PREFIX}/stock-movements/${pathSegment(movementId)}`,
        { signal },
      )
    ).data,

  getCommandOutcome: async (
    commandId: string,
    signal?: AbortSignal,
  ): Promise<AdminCommandOutcome> =>
    (
      await api.get<AdminCommandOutcome>(
        `${PREFIX}/commands/${pathSegment(commandId)}`,
        { signal },
      )
    ).data,

  getReservation: async (
    orderId: string,
    signal?: AbortSignal,
  ): Promise<AdminReservation> =>
    (
      await api.get<AdminReservation>(
        `${PREFIX}/reservations/${pathSegment(orderId)}`,
        { signal },
      )
    ).data,

  recordPhysicalDispatch: async (
    input: PhysicalDispatchRequest,
    commandId: string,
  ): Promise<StockMutationResponse> =>
    (
      await api.post<StockMutationResponse>(
        `${PREFIX}/physical-dispatches`,
        input,
        commandConfig(commandId),
      )
    ).data,
};
