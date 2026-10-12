/**
 * FILE SINH TỰ ĐỘNG — KHÔNG SỬA TAY.
 * Nguồn: contracts/http/inventory/admin/v1/openapi.yaml
 * Sinh lại bằng `scripts/node.sh npm run gen:api`.
 */
export interface paths {
    "/api/v1/admin/inventory/warehouses": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** List warehouses */
        get: operations["listWarehouses"];
        put?: never;
        /** Create an Inventory-owned warehouse */
        post: operations["createWarehouse"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/admin/inventory/warehouses/{warehouse_id}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** Read warehouse master data */
        get: operations["getWarehouse"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        /**
         * Update name, address or new-allocation eligibility
         * @description Requires expected_revision equal to the current warehouse revision; otherwise durable 409 WAREHOUSE_CONFLICT (Inventory ADR-027). Status changes never release, reroute, or rewrite existing commitments.
         */
        patch: operations["updateWarehouse"];
        trace?: never;
    };
    "/api/v1/admin/inventory/stock-receipts": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /** Record initial or supplier stock as sellable */
        post: operations["recordSellableStockReceipt"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/admin/inventory/stock-adjustments": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Apply an audited sellable-stock adjustment
         * @description A negative adjustment can remove only available_to_sell units.
         */
        post: operations["recordStockAdjustment"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/admin/inventory/stock-movements/{movement_id}/reversals": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Append a compensating movement for an eligible receipt or adjustment
         * @description Target must be a successful sellable receipt or adjustment. reversal_sequence is 1..n per original movement and the cumulative reversed quantity cannot exceed the original quantity (409 MOVEMENT_NOT_REVERSIBLE). The sequence is contiguous (next value = accepted reversals + 1) and a reversal movement is not itself reversible. Reversing an increase requires quantity <= available_to_sell (409 INSUFFICIENT_SELLABLE_STOCK); reversing a decrease adds back to sellable.
         */
        post: operations["reverseSellableStockMovement"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/admin/inventory/physical-dispatches": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Record that a committed allocation physically left its warehouse
         * @description This is the sale-flow operation that reduces on_hand and committed. It records an entire line: quantity must equal the immutable ReservationLine quantity at its allocated warehouse and SKU while the line is COMMITTED; otherwise durable 409 DISPATCH_NOT_ALLOWED. Partial-line dispatch does not exist.
         */
        post: operations["recordPhysicalDispatch"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/admin/inventory/return-receipts": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Receive an exact Refund Scope line into quarantine
         * @description (return_receipt_id, return_receipt_line_id) is globally unique. Unknown scope/line, SKU mismatch or over-receipt is 409 RETURN_RECEIPT_REJECTED; a terminal or INSPECTION_COMPLETED scope is 409 REFUND_SCOPE_STATE_CONFLICT; an unknown warehouse is 409 WAREHOUSE_NOT_FOUND.
         */
        post: operations["recordReturnReceipt"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/admin/inventory/inspections": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Record a final PASS or FAIL for received quantity
         * @description PASS moves quarantine to sellable. FAIL remains quarantined and cannot later become PASS. inspection_id is globally unique; the receipt line is addressed by its globally unique (return_receipt_id, return_receipt_line_id) and may be consumed by several inspections.
         */
        post: operations["recordFinalInspection"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/admin/inventory/quarantine-dispositions": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Hold or physically remove failed-inspection stock
         * @description HOLD is a non-terminal annotation with no bucket movement; the quantity stays eligible for a later DISPOSE or RETURN_TO_SUPPLIER. Only those two consume failed, undisposed quantity and require physical departure evidence. (disposition_id, disposition_line_id) is globally unique.
         */
        post: operations["recordQuarantineDisposition"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/admin/inventory/quarantine-dispositions/{disposition_id}/reversals": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Restore physically returned stock to quarantine only
         * @description Only for a DISPOSE or RETURN_TO_SUPPLIER disposition line, selected by the path disposition_id plus body disposition_line_id, whose units are verified physically back at the same warehouse; restores quarantine, never sellable. reversal_sequence is contiguous per disposition line and the cumulative quantity cannot exceed the disposed quantity (409 DISPOSITION_REJECTED).
         */
        post: operations["reverseQuarantineDisposition"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/admin/inventory/stock-positions": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * List stock positions
         * @description Every position, ordered by (warehouse_id, sku_id). warehouse_id and sku_id are optional filters that only narrow the result; omitting both is valid.
         */
        get: operations["listStockPositions"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/admin/inventory/stock-movements": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * Search immutable movement evidence
         * @description Every movement, newest first. warehouse_id, sku_id, source_operation_type and the recorded_from/recorded_to window are optional filters that only narrow the result; omitting all of them is valid.
         */
        get: operations["listStockMovements"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/admin/inventory/dispatch-queue": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * List committed, undispatched lines
         * @description Oldest commit first. Covers every warehouse; warehouse_id optionally narrows to one.
         */
        get: operations["listDispatchQueue"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/admin/inventory/inspection-queue": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * List return receipt lines with uninspected quantity
         * @description Oldest receipt first. Covers every warehouse; warehouse_id optionally narrows to one.
         */
        get: operations["listInspectionQueue"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/admin/inventory/quarantine-items": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * List failed-inspection quantity not yet disposed out
         * @description Oldest inspection first. Covers every warehouse; warehouse_id optionally narrows to one.
         */
        get: operations["listQuarantineItems"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/admin/inventory/refund-scope-summaries": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * List Refund Scopes (return requests)
         * @description Every registered Refund Scope, newest registration first. state optionally narrows the result. Use the scope identifier with getAdminRefundScope for per-line quantities.
         */
        get: operations["listAdminRefundScopes"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/admin/inventory/refund-scopes": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** Resolve the lifetime Refund Scope of an order */
        get: operations["findAdminRefundScopeByOrder"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/admin/inventory/stock-positions/{warehouse_id}/{sku_id}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** Read all authoritative buckets for warehouse operations */
        get: operations["getStockPosition"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/admin/inventory/stock-movements/{movement_id}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** Read immutable movement evidence */
        get: operations["getStockMovement"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/admin/inventory/commands/{command_id}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** Read a durable ADMIN command outcome */
        get: operations["getAdminCommandOutcome"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/admin/inventory/reservations/{order_id}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** Read allocation facts needed for warehouse operations */
        get: operations["getAdminReservationFact"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/admin/inventory/refund-scopes/{refund_scope_id}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** Read immutable expected-return and inspection progress */
        get: operations["getAdminRefundScopeFact"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
}
export type webhooks = Record<string, never>;
export interface components {
    schemas: {
        OpaqueId: string;
        /** Format: int64 */
        Quantity: number;
        EvidenceReference: string;
        Address: {
            line1: string;
            line2?: string;
            locality: string;
            region?: string;
            postal_code?: string;
            country_code: string;
        };
        WarehouseName: string;
        Warehouse: {
            /** @description Generated by Inventory on create. */
            warehouse_id: components["schemas"]["OpaqueId"];
            name: components["schemas"]["WarehouseName"];
            address: components["schemas"]["Address"];
            is_active: boolean;
            /** @enum {unknown} */
            operational_state: "UNKNOWN" | "NORMAL" | "OUTAGE";
            fulfillment_priority: number;
            revision: number;
        };
        CreateWarehouseRequest: {
            name: components["schemas"]["WarehouseName"];
            address: components["schemas"]["Address"];
            is_active: boolean;
            /** @enum {unknown} */
            operational_state: "NORMAL" | "OUTAGE";
            fulfillment_priority: number;
            reason: string;
            evidence_reference: components["schemas"]["EvidenceReference"];
        };
        /** @description At least one of name, address, is_active, operational_state or fulfillment_priority. */
        UpdateWarehouseRequest: {
            /** @description Current warehouse revision; a mismatch is 409 WAREHOUSE_CONFLICT. */
            expected_revision: number;
            name?: components["schemas"]["WarehouseName"];
            address?: components["schemas"]["Address"];
            is_active?: boolean;
            /** @enum {unknown} */
            operational_state?: "NORMAL" | "OUTAGE";
            fulfillment_priority?: number;
            reason: string;
            evidence_reference: components["schemas"]["EvidenceReference"];
        };
        StockReceiptRequest: {
            warehouse_id: components["schemas"]["OpaqueId"];
            sku_id: components["schemas"]["OpaqueId"];
            quantity: components["schemas"]["Quantity"];
            receipt_id: components["schemas"]["OpaqueId"];
            receipt_line_id: components["schemas"]["OpaqueId"];
            /** @constant */
            reason: "INITIAL_STOCK_LOAD";
            baseline_batch_id: components["schemas"]["OpaqueId"];
            evidence_reference: components["schemas"]["EvidenceReference"];
        } | {
            warehouse_id: components["schemas"]["OpaqueId"];
            sku_id: components["schemas"]["OpaqueId"];
            quantity: components["schemas"]["Quantity"];
            receipt_id: components["schemas"]["OpaqueId"];
            receipt_line_id: components["schemas"]["OpaqueId"];
            /** @constant */
            reason: "SUPPLIER_RECEIPT";
            goods_receipt_reference: components["schemas"]["OpaqueId"];
            evidence_reference: components["schemas"]["EvidenceReference"];
        };
        StockAdjustmentRequest: {
            warehouse_id: components["schemas"]["OpaqueId"];
            sku_id: components["schemas"]["OpaqueId"];
            /** @enum {unknown} */
            direction: "INCREASE" | "DECREASE";
            quantity: components["schemas"]["Quantity"];
            adjustment_case_id: components["schemas"]["OpaqueId"];
            adjustment_line_id: components["schemas"]["OpaqueId"];
            /** @enum {unknown} */
            reason: "COUNT_GAIN" | "COUNT_LOSS" | "DAMAGE_REMOVAL" | "EXPIRY_REMOVAL" | "DATA_CORRECTION";
            reason_note?: string;
            evidence_reference: components["schemas"]["EvidenceReference"];
        } & (unknown & unknown & unknown);
        MovementReversalRequest: {
            reversal_sequence: number;
            quantity: components["schemas"]["Quantity"];
            /** @enum {unknown} */
            reason: "DUPLICATE_ENTRY" | "WRONG_QUANTITY" | "WRONG_SKU" | "WRONG_WAREHOUSE" | "SOURCE_DOCUMENT_VOIDED";
            reason_note: string;
            correction_case_reference: components["schemas"]["OpaqueId"];
            evidence_reference: components["schemas"]["EvidenceReference"];
        };
        PhysicalDispatchRequest: {
            dispatch_id: components["schemas"]["OpaqueId"];
            dispatch_line_id: components["schemas"]["OpaqueId"];
            order_id: components["schemas"]["OpaqueId"];
            order_line_id: components["schemas"]["OpaqueId"];
            warehouse_id: components["schemas"]["OpaqueId"];
            sku_id: components["schemas"]["OpaqueId"];
            quantity: components["schemas"]["Quantity"];
            /** Format: date-time */
            handed_over_at: string;
            handover_reference: components["schemas"]["OpaqueId"];
            evidence_reference: components["schemas"]["EvidenceReference"];
        };
        ReturnReceiptRequest: {
            refund_scope_id: components["schemas"]["OpaqueId"];
            order_line_id: components["schemas"]["OpaqueId"];
            actual_sku_id: components["schemas"]["OpaqueId"];
            warehouse_id: components["schemas"]["OpaqueId"];
            quantity: components["schemas"]["Quantity"];
            return_receipt_id: components["schemas"]["OpaqueId"];
            return_receipt_line_id: components["schemas"]["OpaqueId"];
            package_reference: components["schemas"]["OpaqueId"];
            /** Format: date-time */
            received_at: string;
            evidence_reference: components["schemas"]["EvidenceReference"];
        };
        InspectionRequest: {
            inspection_id: components["schemas"]["OpaqueId"];
            return_receipt_id: components["schemas"]["OpaqueId"];
            return_receipt_line_id: components["schemas"]["OpaqueId"];
            warehouse_id: components["schemas"]["OpaqueId"];
            quantity: components["schemas"]["Quantity"];
            /** @constant */
            result: "PASS";
            checklist_reference: components["schemas"]["OpaqueId"];
            evidence_reference: components["schemas"]["EvidenceReference"];
        } | {
            inspection_id: components["schemas"]["OpaqueId"];
            return_receipt_id: components["schemas"]["OpaqueId"];
            return_receipt_line_id: components["schemas"]["OpaqueId"];
            warehouse_id: components["schemas"]["OpaqueId"];
            quantity: components["schemas"]["Quantity"];
            /** @constant */
            result: "FAIL";
            /** @enum {unknown} */
            fail_reason: "DAMAGED" | "USED_OR_ALTERED" | "MISSING_COMPONENTS" | "SAFETY_OR_HYGIENE" | "COUNTERFEIT_OR_UNVERIFIABLE" | "NOT_RESELLABLE_BY_POLICY";
            checklist_reference: components["schemas"]["OpaqueId"];
            evidence_reference: components["schemas"]["EvidenceReference"];
        };
        QuarantineDispositionRequest: {
            disposition_id: components["schemas"]["OpaqueId"];
            disposition_line_id: components["schemas"]["OpaqueId"];
            inspection_id: components["schemas"]["OpaqueId"];
            warehouse_id: components["schemas"]["OpaqueId"];
            sku_id: components["schemas"]["OpaqueId"];
            quantity: components["schemas"]["Quantity"];
            /** @constant */
            disposition: "HOLD";
            /** @enum {unknown} */
            reason: "AWAITING_DISPOSITION" | "SUPPLIER_CLAIM" | "SAFETY_REVIEW" | "EVIDENCE_PRESERVATION";
            evidence_reference: components["schemas"]["EvidenceReference"];
        } | {
            disposition_id: components["schemas"]["OpaqueId"];
            disposition_line_id: components["schemas"]["OpaqueId"];
            inspection_id: components["schemas"]["OpaqueId"];
            warehouse_id: components["schemas"]["OpaqueId"];
            sku_id: components["schemas"]["OpaqueId"];
            quantity: components["schemas"]["Quantity"];
            /** @constant */
            disposition: "DISPOSE";
            /** @enum {unknown} */
            reason: "UNSAFE" | "UNREPAIRABLE" | "EXPIRED" | "POLICY_REQUIRED";
            physical_departure_reference: components["schemas"]["OpaqueId"];
            /** Format: date-time */
            departed_at: string;
            evidence_reference: components["schemas"]["EvidenceReference"];
        } | {
            disposition_id: components["schemas"]["OpaqueId"];
            disposition_line_id: components["schemas"]["OpaqueId"];
            inspection_id: components["schemas"]["OpaqueId"];
            warehouse_id: components["schemas"]["OpaqueId"];
            sku_id: components["schemas"]["OpaqueId"];
            quantity: components["schemas"]["Quantity"];
            /** @constant */
            disposition: "RETURN_TO_SUPPLIER";
            /** @constant */
            reason: "SUPPLIER_RETURN_AUTHORIZED";
            physical_departure_reference: components["schemas"]["OpaqueId"];
            /** Format: date-time */
            departed_at: string;
            evidence_reference: components["schemas"]["EvidenceReference"];
        };
        DispositionReversalRequest: {
            disposition_line_id: components["schemas"]["OpaqueId"];
            reversal_sequence: number;
            quantity: components["schemas"]["Quantity"];
            /** @enum {unknown} */
            reason: "WRONG_RECORD" | "DISPOSAL_ABORTED" | "SUPPLIER_RETURNED";
            reason_note: string;
            /** Format: date-time */
            physically_returned_at: string;
            physical_return_reference: components["schemas"]["OpaqueId"];
            evidence_reference: components["schemas"]["EvidenceReference"];
        };
        StockPosition: {
            warehouse_id: components["schemas"]["OpaqueId"];
            sku_id: components["schemas"]["OpaqueId"];
            /** Format: int64 */
            on_hand: number;
            /** Format: int64 */
            available_to_sell: number;
            /** Format: int64 */
            reserved: number;
            /** Format: int64 */
            committed: number;
            /** Format: int64 */
            quarantine: number;
            revision: number;
        };
        StockMovement: {
            movement_id: components["schemas"]["OpaqueId"];
            warehouse_id: components["schemas"]["OpaqueId"];
            sku_id: components["schemas"]["OpaqueId"];
            source_operation_type: string;
            source_operation_id: components["schemas"]["OpaqueId"];
            reverses_movement_id?: components["schemas"]["OpaqueId"];
            deltas: {
                /** Format: int64 */
                on_hand: number;
                /** Format: int64 */
                available_to_sell: number;
                /** Format: int64 */
                reserved: number;
                /** Format: int64 */
                committed: number;
                /** Format: int64 */
                quarantine: number;
            };
            /** Format: date-time */
            recorded_at: string;
        };
        AdminCommandOutcome: {
            command_id: components["schemas"]["OpaqueId"];
            correlation_id: components["schemas"]["OpaqueId"];
            operation: string;
            /** @enum {unknown} */
            result: "ACCEPTED" | "REJECTED";
            outcome: string;
            /** @description Original HTTP status; 409 for a durable rejection. */
            http_status?: number;
            /** @description Catalog code; present only when result is REJECTED. */
            error_code?: string;
            resource_id?: components["schemas"]["OpaqueId"];
            /** Format: date-time */
            recorded_at: string;
        };
        /** @description Opaque cursor for the next page; null on the last page. */
        PageCursor: string | null;
        WarehousePage: {
            items: components["schemas"]["Warehouse"][];
            next_cursor: components["schemas"]["PageCursor"];
        };
        StockPositionPage: {
            items: components["schemas"]["StockPosition"][];
            next_cursor: components["schemas"]["PageCursor"];
        };
        StockMovementPage: {
            items: components["schemas"]["StockMovement"][];
            next_cursor: components["schemas"]["PageCursor"];
        };
        DispatchQueueItem: {
            order_id: components["schemas"]["OpaqueId"];
            order_line_id: components["schemas"]["OpaqueId"];
            sku_id: components["schemas"]["OpaqueId"];
            quantity: components["schemas"]["Quantity"];
            warehouse_id: components["schemas"]["OpaqueId"];
            /** Format: date-time */
            committed_at: string;
        };
        DispatchQueuePage: {
            items: components["schemas"]["DispatchQueueItem"][];
            next_cursor: components["schemas"]["PageCursor"];
        };
        InspectionQueueItem: {
            refund_scope_id: components["schemas"]["OpaqueId"];
            order_id: components["schemas"]["OpaqueId"];
            order_line_id: components["schemas"]["OpaqueId"];
            return_receipt_id: components["schemas"]["OpaqueId"];
            return_receipt_line_id: components["schemas"]["OpaqueId"];
            sku_id: components["schemas"]["OpaqueId"];
            warehouse_id: components["schemas"]["OpaqueId"];
            received_quantity: components["schemas"]["Quantity"];
            uninspected_quantity: components["schemas"]["Quantity"];
            /** Format: date-time */
            received_at: string;
        };
        InspectionQueuePage: {
            items: components["schemas"]["InspectionQueueItem"][];
            next_cursor: components["schemas"]["PageCursor"];
        };
        QuarantineItem: {
            inspection_id: components["schemas"]["OpaqueId"];
            warehouse_id: components["schemas"]["OpaqueId"];
            sku_id: components["schemas"]["OpaqueId"];
            /** @enum {unknown} */
            fail_reason: "DAMAGED" | "USED_OR_ALTERED" | "MISSING_COMPONENTS" | "SAFETY_OR_HYGIENE" | "COUNTERFEIT_OR_UNVERIFIABLE" | "NOT_RESELLABLE_BY_POLICY";
            failed_quantity: components["schemas"]["Quantity"];
            /** @description Failed quantity not yet consumed by DISPOSE or RETURN_TO_SUPPLIER; HOLD does not reduce it. */
            disposable_quantity: components["schemas"]["Quantity"];
            /** @enum {unknown} */
            latest_hold_reason?: "AWAITING_DISPOSITION" | "SUPPLIER_CLAIM" | "SAFETY_REVIEW" | "EVIDENCE_PRESERVATION";
            /** Format: date-time */
            inspected_at: string;
        };
        QuarantineItemPage: {
            items: components["schemas"]["QuarantineItem"][];
            next_cursor: components["schemas"]["PageCursor"];
        };
        AdminReservationLine: {
            order_line_id: components["schemas"]["OpaqueId"];
            sku_id: components["schemas"]["OpaqueId"];
            quantity: components["schemas"]["Quantity"];
            warehouse_id: components["schemas"]["OpaqueId"];
            /** @enum {unknown} */
            state: "UNKNOWN" | "RESERVED" | "COMMITTED" | "DISPATCHED" | "RELEASED";
        };
        AdminReservation: {
            order_id: components["schemas"]["OpaqueId"];
            /** @enum {unknown} */
            state: "UNKNOWN" | "RESERVED" | "COMMITTED" | "PARTIALLY_DISPATCHED" | "DISPATCHED" | "RELEASED";
            revision: number;
            lines: components["schemas"]["AdminReservationLine"][];
        };
        AdminRefundScopeLine: {
            order_line_id: components["schemas"]["OpaqueId"];
            sku_id: components["schemas"]["OpaqueId"];
            expected_quantity: components["schemas"]["Quantity"];
            /** Format: int64 */
            received_quantity: number;
            /** Format: int64 */
            passed_quantity: number;
            /** Format: int64 */
            failed_quantity: number;
        };
        AdminRefundScope: {
            refund_scope_id: components["schemas"]["OpaqueId"];
            refund_request_id: components["schemas"]["OpaqueId"];
            order_id: components["schemas"]["OpaqueId"];
            /** @enum {unknown} */
            state: "UNKNOWN" | "REGISTERED" | "IN_PROGRESS" | "INSPECTION_COMPLETED" | "CANCELLED" | "CLOSED" | "CLOSED_INCOMPLETE";
            revision: number;
            lines: components["schemas"]["AdminRefundScopeLine"][];
        };
        AdminRefundScopeSummary: {
            refund_scope_id: components["schemas"]["OpaqueId"];
            refund_request_id: components["schemas"]["OpaqueId"];
            order_id: components["schemas"]["OpaqueId"];
            /** @enum {unknown} */
            state: "REGISTERED" | "IN_PROGRESS" | "INSPECTION_COMPLETED" | "CANCELLED" | "CLOSED" | "CLOSED_INCOMPLETE";
            revision: number;
            /** Format: date-time */
            registered_at: string;
        };
        AdminRefundScopeSummaryPage: {
            items: components["schemas"]["AdminRefundScopeSummary"][];
            next_cursor: components["schemas"]["PageCursor"];
        };
        StockMutationResponse: {
            command_id: components["schemas"]["OpaqueId"];
            correlation_id: components["schemas"]["OpaqueId"];
            /** @constant */
            outcome: "ACCEPTED";
            operation_id: components["schemas"]["OpaqueId"];
            /** @description Present only when the accepted operation changes a stock bucket. */
            movement_id?: components["schemas"]["OpaqueId"];
            /** Format: date-time */
            recorded_at: string;
            /** @default false */
            replayed: boolean;
        };
        WarehouseMutationResponse: {
            command_id: components["schemas"]["OpaqueId"];
            correlation_id: components["schemas"]["OpaqueId"];
            /** @constant */
            outcome: "ACCEPTED";
            warehouse: components["schemas"]["Warehouse"];
            /** Format: date-time */
            recorded_at: string;
            /** @default false */
            replayed: boolean;
        };
        InventoryProblem: components["schemas"]["problem.schema"] & {
            correlation_id: string;
            command_id?: string;
        };
        /**
         * Problem Details
         * @description RFC 9457 Problem Details với extension chung của dự án.
         */
        "problem.schema": {
            /** Format: uri-reference */
            type: string;
            title: string;
            status: number;
            detail: string;
            /** Format: uri-reference */
            instance: string;
            code: string;
            trace_id?: string;
            /** @default false */
            retryable: boolean;
            errors?: {
                field: string;
                message: string;
            }[];
        } & {
            [key: string]: unknown;
        };
    };
    responses: {
        /** @description Durable warehouse command outcome. */
        WarehouseSuccess: {
            headers: {
                [name: string]: unknown;
            };
            content: {
                "application/json": components["schemas"]["WarehouseMutationResponse"];
            };
        };
        /** @description Durable accepted stock operation or exact idempotent replay. */
        StockMutationSuccess: {
            headers: {
                [name: string]: unknown;
            };
            content: {
                "application/json": components["schemas"]["StockMutationResponse"];
            };
        };
        /** @description INVALID_REQUEST; correct input and use a new command ID. */
        BadRequest: {
            headers: {
                [name: string]: unknown;
            };
            content: {
                "application/problem+json": components["schemas"]["InventoryProblem"];
            };
        };
        /** @description Missing or invalid identity. */
        Unauthorized: {
            headers: {
                [name: string]: unknown;
            };
            content: {
                "application/problem+json": components["schemas"]["InventoryProblem"];
            };
        };
        /** @description ADMIN authority or MFA is missing. */
        Forbidden: {
            headers: {
                [name: string]: unknown;
            };
            content: {
                "application/problem+json": components["schemas"]["InventoryProblem"];
            };
        };
        /** @description Resource not found; for a command path target (warehouse update, movement or disposition reversal) this is a durable outcome replayed with the same body. */
        NotFound: {
            headers: {
                [name: string]: unknown;
            };
            content: {
                "application/problem+json": components["schemas"]["InventoryProblem"];
            };
        };
        /** @description Durable conflict replayed with the same body. code is one of IDEMPOTENCY_KEY_REUSED, OPERATION_IDENTITY_CONFLICT, INSUFFICIENT_SELLABLE_STOCK, DISPATCH_NOT_ALLOWED, RETURN_RECEIPT_REJECTED, INSPECTION_REJECTED, DISPOSITION_REJECTED, MOVEMENT_NOT_REVERSIBLE, REFUND_SCOPE_STATE_CONFLICT, WAREHOUSE_CONFLICT or WAREHOUSE_NOT_FOUND (unknown warehouse_id in a command body, Inventory ADR-027). */
        Conflict: {
            headers: {
                [name: string]: unknown;
            };
            content: {
                "application/problem+json": components["schemas"]["InventoryProblem"];
            };
        };
        /** @description INVENTORY_UNAVAILABLE (store unavailable, write freeze or outbox backpressure) or INVENTORY_BUSY (Retry-After 1); retry the same command ID. */
        Unavailable: {
            headers: {
                /** @description Suggested delay in seconds when known. */
                "Retry-After"?: number;
                [name: string]: unknown;
            };
            content: {
                "application/problem+json": components["schemas"]["InventoryProblem"];
            };
        };
    };
    parameters: {
        /** @description Caller-scoped command ID retained with its fingerprint and result for at least seven years. */
        IdempotencyKey: string;
        CorrelationId: string;
        /** @description For an initial ADMIN command use the command ID. */
        CausationId: string;
        WarehouseId: components["schemas"]["OpaqueId"];
        SkuId: components["schemas"]["OpaqueId"];
        MovementId: components["schemas"]["OpaqueId"];
        DispositionId: components["schemas"]["OpaqueId"];
        CommandId: components["schemas"]["OpaqueId"];
        OrderId: components["schemas"]["OpaqueId"];
        RefundScopeId: components["schemas"]["OpaqueId"];
        /** @description Opaque cursor from the previous page. */
        Cursor: string;
        Limit: number;
    };
    requestBodies: never;
    headers: never;
    pathItems: never;
}
export type $defs = Record<string, never>;
export interface operations {
    listWarehouses: {
        parameters: {
            query?: {
                is_active?: boolean;
                /** @description Opaque cursor from the previous page. */
                cursor?: components["parameters"]["Cursor"];
                limit?: components["parameters"]["Limit"];
            };
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Warehouse page. */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["WarehousePage"];
                };
            };
            400: components["responses"]["BadRequest"];
            401: components["responses"]["Unauthorized"];
            403: components["responses"]["Forbidden"];
            503: components["responses"]["Unavailable"];
        };
    };
    createWarehouse: {
        parameters: {
            query?: never;
            header: {
                /** @description Caller-scoped command ID retained with its fingerprint and result for at least seven years. */
                "Idempotency-Key": components["parameters"]["IdempotencyKey"];
                "X-Correlation-ID": components["parameters"]["CorrelationId"];
                /** @description For an initial ADMIN command use the command ID. */
                "X-Causation-ID": components["parameters"]["CausationId"];
            };
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["CreateWarehouseRequest"];
            };
        };
        responses: {
            200: components["responses"]["WarehouseSuccess"];
            400: components["responses"]["BadRequest"];
            401: components["responses"]["Unauthorized"];
            403: components["responses"]["Forbidden"];
            409: components["responses"]["Conflict"];
            503: components["responses"]["Unavailable"];
        };
    };
    getWarehouse: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                warehouse_id: components["parameters"]["WarehouseId"];
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Warehouse. */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["Warehouse"];
                };
            };
            401: components["responses"]["Unauthorized"];
            403: components["responses"]["Forbidden"];
            404: components["responses"]["NotFound"];
            503: components["responses"]["Unavailable"];
        };
    };
    updateWarehouse: {
        parameters: {
            query?: never;
            header: {
                /** @description Caller-scoped command ID retained with its fingerprint and result for at least seven years. */
                "Idempotency-Key": components["parameters"]["IdempotencyKey"];
                "X-Correlation-ID": components["parameters"]["CorrelationId"];
                /** @description For an initial ADMIN command use the command ID. */
                "X-Causation-ID": components["parameters"]["CausationId"];
            };
            path: {
                warehouse_id: components["parameters"]["WarehouseId"];
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["UpdateWarehouseRequest"];
            };
        };
        responses: {
            200: components["responses"]["WarehouseSuccess"];
            400: components["responses"]["BadRequest"];
            401: components["responses"]["Unauthorized"];
            403: components["responses"]["Forbidden"];
            404: components["responses"]["NotFound"];
            409: components["responses"]["Conflict"];
            503: components["responses"]["Unavailable"];
        };
    };
    recordSellableStockReceipt: {
        parameters: {
            query?: never;
            header: {
                /** @description Caller-scoped command ID retained with its fingerprint and result for at least seven years. */
                "Idempotency-Key": components["parameters"]["IdempotencyKey"];
                "X-Correlation-ID": components["parameters"]["CorrelationId"];
                /** @description For an initial ADMIN command use the command ID. */
                "X-Causation-ID": components["parameters"]["CausationId"];
            };
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["StockReceiptRequest"];
            };
        };
        responses: {
            200: components["responses"]["StockMutationSuccess"];
            400: components["responses"]["BadRequest"];
            401: components["responses"]["Unauthorized"];
            403: components["responses"]["Forbidden"];
            409: components["responses"]["Conflict"];
            503: components["responses"]["Unavailable"];
        };
    };
    recordStockAdjustment: {
        parameters: {
            query?: never;
            header: {
                /** @description Caller-scoped command ID retained with its fingerprint and result for at least seven years. */
                "Idempotency-Key": components["parameters"]["IdempotencyKey"];
                "X-Correlation-ID": components["parameters"]["CorrelationId"];
                /** @description For an initial ADMIN command use the command ID. */
                "X-Causation-ID": components["parameters"]["CausationId"];
            };
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["StockAdjustmentRequest"];
            };
        };
        responses: {
            200: components["responses"]["StockMutationSuccess"];
            400: components["responses"]["BadRequest"];
            401: components["responses"]["Unauthorized"];
            403: components["responses"]["Forbidden"];
            409: components["responses"]["Conflict"];
            503: components["responses"]["Unavailable"];
        };
    };
    reverseSellableStockMovement: {
        parameters: {
            query?: never;
            header: {
                /** @description Caller-scoped command ID retained with its fingerprint and result for at least seven years. */
                "Idempotency-Key": components["parameters"]["IdempotencyKey"];
                "X-Correlation-ID": components["parameters"]["CorrelationId"];
                /** @description For an initial ADMIN command use the command ID. */
                "X-Causation-ID": components["parameters"]["CausationId"];
            };
            path: {
                movement_id: components["parameters"]["MovementId"];
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["MovementReversalRequest"];
            };
        };
        responses: {
            200: components["responses"]["StockMutationSuccess"];
            400: components["responses"]["BadRequest"];
            401: components["responses"]["Unauthorized"];
            403: components["responses"]["Forbidden"];
            404: components["responses"]["NotFound"];
            409: components["responses"]["Conflict"];
            503: components["responses"]["Unavailable"];
        };
    };
    recordPhysicalDispatch: {
        parameters: {
            query?: never;
            header: {
                /** @description Caller-scoped command ID retained with its fingerprint and result for at least seven years. */
                "Idempotency-Key": components["parameters"]["IdempotencyKey"];
                "X-Correlation-ID": components["parameters"]["CorrelationId"];
                /** @description For an initial ADMIN command use the command ID. */
                "X-Causation-ID": components["parameters"]["CausationId"];
            };
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["PhysicalDispatchRequest"];
            };
        };
        responses: {
            200: components["responses"]["StockMutationSuccess"];
            400: components["responses"]["BadRequest"];
            401: components["responses"]["Unauthorized"];
            403: components["responses"]["Forbidden"];
            409: components["responses"]["Conflict"];
            503: components["responses"]["Unavailable"];
        };
    };
    recordReturnReceipt: {
        parameters: {
            query?: never;
            header: {
                /** @description Caller-scoped command ID retained with its fingerprint and result for at least seven years. */
                "Idempotency-Key": components["parameters"]["IdempotencyKey"];
                "X-Correlation-ID": components["parameters"]["CorrelationId"];
                /** @description For an initial ADMIN command use the command ID. */
                "X-Causation-ID": components["parameters"]["CausationId"];
            };
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["ReturnReceiptRequest"];
            };
        };
        responses: {
            200: components["responses"]["StockMutationSuccess"];
            400: components["responses"]["BadRequest"];
            401: components["responses"]["Unauthorized"];
            403: components["responses"]["Forbidden"];
            409: components["responses"]["Conflict"];
            503: components["responses"]["Unavailable"];
        };
    };
    recordFinalInspection: {
        parameters: {
            query?: never;
            header: {
                /** @description Caller-scoped command ID retained with its fingerprint and result for at least seven years. */
                "Idempotency-Key": components["parameters"]["IdempotencyKey"];
                "X-Correlation-ID": components["parameters"]["CorrelationId"];
                /** @description For an initial ADMIN command use the command ID. */
                "X-Causation-ID": components["parameters"]["CausationId"];
            };
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["InspectionRequest"];
            };
        };
        responses: {
            200: components["responses"]["StockMutationSuccess"];
            400: components["responses"]["BadRequest"];
            401: components["responses"]["Unauthorized"];
            403: components["responses"]["Forbidden"];
            409: components["responses"]["Conflict"];
            503: components["responses"]["Unavailable"];
        };
    };
    recordQuarantineDisposition: {
        parameters: {
            query?: never;
            header: {
                /** @description Caller-scoped command ID retained with its fingerprint and result for at least seven years. */
                "Idempotency-Key": components["parameters"]["IdempotencyKey"];
                "X-Correlation-ID": components["parameters"]["CorrelationId"];
                /** @description For an initial ADMIN command use the command ID. */
                "X-Causation-ID": components["parameters"]["CausationId"];
            };
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["QuarantineDispositionRequest"];
            };
        };
        responses: {
            200: components["responses"]["StockMutationSuccess"];
            400: components["responses"]["BadRequest"];
            401: components["responses"]["Unauthorized"];
            403: components["responses"]["Forbidden"];
            409: components["responses"]["Conflict"];
            503: components["responses"]["Unavailable"];
        };
    };
    reverseQuarantineDisposition: {
        parameters: {
            query?: never;
            header: {
                /** @description Caller-scoped command ID retained with its fingerprint and result for at least seven years. */
                "Idempotency-Key": components["parameters"]["IdempotencyKey"];
                "X-Correlation-ID": components["parameters"]["CorrelationId"];
                /** @description For an initial ADMIN command use the command ID. */
                "X-Causation-ID": components["parameters"]["CausationId"];
            };
            path: {
                disposition_id: components["parameters"]["DispositionId"];
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["DispositionReversalRequest"];
            };
        };
        responses: {
            200: components["responses"]["StockMutationSuccess"];
            400: components["responses"]["BadRequest"];
            401: components["responses"]["Unauthorized"];
            403: components["responses"]["Forbidden"];
            404: components["responses"]["NotFound"];
            409: components["responses"]["Conflict"];
            503: components["responses"]["Unavailable"];
        };
    };
    listStockPositions: {
        parameters: {
            query?: {
                warehouse_id?: components["schemas"]["OpaqueId"];
                sku_id?: components["schemas"]["OpaqueId"];
                /** @description Opaque cursor from the previous page. */
                cursor?: components["parameters"]["Cursor"];
                limit?: components["parameters"]["Limit"];
            };
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Stock position page. */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["StockPositionPage"];
                };
            };
            400: components["responses"]["BadRequest"];
            401: components["responses"]["Unauthorized"];
            403: components["responses"]["Forbidden"];
            503: components["responses"]["Unavailable"];
        };
    };
    listStockMovements: {
        parameters: {
            query?: {
                warehouse_id?: components["schemas"]["OpaqueId"];
                sku_id?: components["schemas"]["OpaqueId"];
                /** @description Exact movement source, for example SELLABLE_RECEIPT for stock receipts or RETURN_RECEIPT for returned goods. */
                source_operation_type?: string;
                recorded_from?: string;
                recorded_to?: string;
                /** @description Opaque cursor from the previous page. */
                cursor?: components["parameters"]["Cursor"];
                limit?: components["parameters"]["Limit"];
            };
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Movement page. */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["StockMovementPage"];
                };
            };
            400: components["responses"]["BadRequest"];
            401: components["responses"]["Unauthorized"];
            403: components["responses"]["Forbidden"];
            503: components["responses"]["Unavailable"];
        };
    };
    listDispatchQueue: {
        parameters: {
            query?: {
                warehouse_id?: components["schemas"]["OpaqueId"];
                /** @description Opaque cursor from the previous page. */
                cursor?: components["parameters"]["Cursor"];
                limit?: components["parameters"]["Limit"];
            };
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Dispatch queue page. */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["DispatchQueuePage"];
                };
            };
            400: components["responses"]["BadRequest"];
            401: components["responses"]["Unauthorized"];
            403: components["responses"]["Forbidden"];
            503: components["responses"]["Unavailable"];
        };
    };
    listInspectionQueue: {
        parameters: {
            query?: {
                warehouse_id?: components["schemas"]["OpaqueId"];
                /** @description Opaque cursor from the previous page. */
                cursor?: components["parameters"]["Cursor"];
                limit?: components["parameters"]["Limit"];
            };
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Inspection queue page. */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["InspectionQueuePage"];
                };
            };
            400: components["responses"]["BadRequest"];
            401: components["responses"]["Unauthorized"];
            403: components["responses"]["Forbidden"];
            503: components["responses"]["Unavailable"];
        };
    };
    listQuarantineItems: {
        parameters: {
            query?: {
                warehouse_id?: components["schemas"]["OpaqueId"];
                /** @description Opaque cursor from the previous page. */
                cursor?: components["parameters"]["Cursor"];
                limit?: components["parameters"]["Limit"];
            };
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Quarantine item page. */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["QuarantineItemPage"];
                };
            };
            400: components["responses"]["BadRequest"];
            401: components["responses"]["Unauthorized"];
            403: components["responses"]["Forbidden"];
            503: components["responses"]["Unavailable"];
        };
    };
    listAdminRefundScopes: {
        parameters: {
            query?: {
                state?: "REGISTERED" | "IN_PROGRESS" | "INSPECTION_COMPLETED" | "CANCELLED" | "CLOSED" | "CLOSED_INCOMPLETE";
                /** @description Opaque cursor from the previous page. */
                cursor?: components["parameters"]["Cursor"];
                limit?: components["parameters"]["Limit"];
            };
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Refund Scope summary page. */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["AdminRefundScopeSummaryPage"];
                };
            };
            400: components["responses"]["BadRequest"];
            401: components["responses"]["Unauthorized"];
            403: components["responses"]["Forbidden"];
            503: components["responses"]["Unavailable"];
        };
    };
    findAdminRefundScopeByOrder: {
        parameters: {
            query: {
                order_id: components["schemas"]["OpaqueId"];
            };
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Refund Scope operational view. */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["AdminRefundScope"];
                };
            };
            400: components["responses"]["BadRequest"];
            401: components["responses"]["Unauthorized"];
            403: components["responses"]["Forbidden"];
            404: components["responses"]["NotFound"];
            503: components["responses"]["Unavailable"];
        };
    };
    getStockPosition: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                warehouse_id: components["parameters"]["WarehouseId"];
                sku_id: components["parameters"]["SkuId"];
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Stock position. */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["StockPosition"];
                };
            };
            401: components["responses"]["Unauthorized"];
            403: components["responses"]["Forbidden"];
            404: components["responses"]["NotFound"];
            503: components["responses"]["Unavailable"];
        };
    };
    getStockMovement: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                movement_id: components["parameters"]["MovementId"];
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Movement evidence. */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["StockMovement"];
                };
            };
            401: components["responses"]["Unauthorized"];
            403: components["responses"]["Forbidden"];
            404: components["responses"]["NotFound"];
            503: components["responses"]["Unavailable"];
        };
    };
    getAdminCommandOutcome: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                command_id: components["parameters"]["CommandId"];
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Command outcome. */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["AdminCommandOutcome"];
                };
            };
            401: components["responses"]["Unauthorized"];
            403: components["responses"]["Forbidden"];
            404: components["responses"]["NotFound"];
            503: components["responses"]["Unavailable"];
        };
    };
    getAdminReservationFact: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                order_id: components["parameters"]["OrderId"];
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Reservation operational view. */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["AdminReservation"];
                };
            };
            401: components["responses"]["Unauthorized"];
            403: components["responses"]["Forbidden"];
            404: components["responses"]["NotFound"];
            503: components["responses"]["Unavailable"];
        };
    };
    getAdminRefundScopeFact: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                refund_scope_id: components["parameters"]["RefundScopeId"];
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Refund Scope operational view. */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["AdminRefundScope"];
                };
            };
            401: components["responses"]["Unauthorized"];
            403: components["responses"]["Forbidden"];
            404: components["responses"]["NotFound"];
            503: components["responses"]["Unavailable"];
        };
    };
}
