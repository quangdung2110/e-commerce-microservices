import { describe, expect, it } from "vitest";

import type { MarkdownRule, Promotion } from "@/lib/api/promotion";
import {
  campaignPatch,
  markdownPatch,
  markdownToDraft,
  normalizeCode,
  validateCampaign,
  validateMarkdown,
  type CampaignDraft,
} from "@/lib/promotion/campaign";
import {
  basisPointsToPercent,
  describeDiscount,
  discountFromDraft,
  percentToBasisPoints,
  validateDiscount,
} from "@/lib/promotion/discount";
import {
  ALWAYS_TRUE_RULE,
  changeOperator,
  describeRule,
  emptyGroup,
  fromWire,
  isAlwaysTrue,
  newCondition,
  toWire,
  validateRule,
  type DraftCondition,
  type DraftNode,
} from "@/lib/promotion/rule";
import {
  emptyVoucherDraft,
  validateBudget,
  validateValidity,
  validateVoucher,
  validityFromDraft,
  validityToDraft,
  voucherCreatePayload,
  voucherPatch,
  voucherToDraft,
} from "@/lib/promotion/voucher";

const vnd = (amount: number) => ({ amount_minor: amount, currency: "VND" as const });
const UUID = "0199aaaa-bbbb-7ccc-8ddd-eeeeffff0000";

function condition(overrides: Partial<DraftCondition>): DraftCondition {
  return { ...newCondition("cart.total_amount"), ...overrides };
}

describe("Rule AST", () => {
  it("coi điều kiện tổng tiền ≥ 0 là “mọi đơn hàng”", () => {
    expect(isAlwaysTrue(ALWAYS_TRUE_RULE)).toBe(true);
    expect(describeRule(ALWAYS_TRUE_RULE)).toBe("Mọi đơn hàng");
    expect(validateRule(fromWire(ALWAYS_TRUE_RULE))).toEqual([]);
  });

  it("từ chối nhóm rỗng và giá trị còn thiếu", () => {
    expect(validateRule(emptyGroup())).toEqual(["Điều kiện: nhóm chưa có điều kiện nào."]);
    const incomplete: DraftNode = { ...emptyGroup(), children: [condition({ value: null })] };
    expect(validateRule(incomplete)).toEqual([
      "Điều kiện › 1: hãy nhập số tiền nguyên từ 0 trở lên.",
    ]);
  });

  it("danh mục dùng IN/NOT_IN với mảng chuỗi không trùng", () => {
    const categories = newCondition("cart.items.category_id");
    expect(categories.operator).toBe("IN");
    expect(validateRule({ ...categories, value: [] })).toEqual([
      "Điều kiện: hãy chọn ít nhất một danh mục.",
    ]);
    expect(validateRule({ ...categories, value: ["a", "a"] })).toEqual([
      "Điều kiện: các giá trị không được trùng nhau.",
    ]);
    expect(validateRule({ ...categories, value: ["a", "b"] })).toEqual([]);
  });

  it("chặn phép so sánh không hợp với trường và UUID sai", () => {
    expect(
      validateRule(condition({ field: "cart.items.category_id", operator: "GREATER_THAN", value: "x" }))
    ).toEqual(["Điều kiện: “Danh mục của sản phẩm trong đơn” không dùng được phép so sánh này."]);
    const user = newCondition("user.id");
    expect(validateRule({ ...user, operator: "EQUAL", value: "not-a-uuid" })).toEqual([
      "Điều kiện: mã người dùng phải là UUID hợp lệ.",
    ]);
    expect(validateRule({ ...user, operator: "EQUAL", value: UUID })).toEqual([]);
  });

  it("từ chối trường ngoài allowlist", () => {
    expect(validateRule(condition({ field: "cart.secret", value: 1 }))).toEqual([
      "Điều kiện: trường “cart.secret” không được hỗ trợ.",
    ]);
  });

  it("đổi operator giữa đơn và danh sách giữ lại giá trị đầu", () => {
    const user = { ...newCondition("user.id"), operator: "IN" as const, value: [UUID, "b"] };
    const single = changeOperator(user, "EQUAL");
    expect(single.value).toBe(UUID);
    expect(changeOperator(single, "NOT_IN").value).toEqual([UUID]);
  });

  it("giới hạn độ sâu lồng nhau", () => {
    let node: DraftNode = condition({ value: 1 });
    for (let level = 0; level < 8; level += 1) {
      node = { ...emptyGroup(), children: [node] };
    }
    expect(validateRule(node)[0]).toContain("lồng nhau quá 8 cấp");
  });

  it("mô tả rule bằng tiếng Việt kèm tên danh mục", () => {
    const rule: DraftNode = {
      ...emptyGroup("AND"),
      children: [
        condition({ operator: "GREATER_THAN_OR_EQUAL", value: 200000 }),
        { ...newCondition("cart.items.category_id"), value: ["cat-1"] },
      ],
    };
    const text = describeRule(rule, (id) => (id === "cat-1" ? "Điện thoại" : id));
    expect(text).toContain("Tổng tiền hàng của đơn từ (≥)");
    expect(text).toContain("thuộc một trong [Điện thoại]");
    expect(text).toContain(" và ");
  });

  it("toWire giữ nguyên cấu trúc AST của contract", () => {
    const wire = { ...ALWAYS_TRUE_RULE };
    expect(toWire(fromWire(wire))).toEqual(wire);
  });
});

describe("mức giảm", () => {
  it("đổi phần trăm sang basis point bằng số nguyên", () => {
    expect(percentToBasisPoints("12.5")).toBe(1250);
    expect(percentToBasisPoints("12,5")).toBe(1250);
    expect(percentToBasisPoints("0.01")).toBe(1);
    expect(percentToBasisPoints("100")).toBe(10000);
    expect(percentToBasisPoints("100.01")).toBeNull();
    expect(percentToBasisPoints("0")).toBeNull();
    expect(percentToBasisPoints("1.234")).toBeNull();
    expect(basisPointsToPercent(1250)).toBe("12.5");
  });

  it("dựng discount đúng discriminator của contract", () => {
    expect(
      discountFromDraft({ type: "PERCENTAGE", percent: "10", maxDiscount: 50000, fixed: undefined })
    ).toEqual({ type: "PERCENTAGE", value_basis_points: 1000, max_discount: vnd(50000) });
    expect(
      discountFromDraft({ type: "PERCENTAGE", percent: "10", maxDiscount: undefined, fixed: undefined })
    ).toEqual({ type: "PERCENTAGE", value_basis_points: 1000, max_discount: null });
    expect(
      discountFromDraft({ type: "FIXED_VND", percent: "", maxDiscount: undefined, fixed: 30000 })
    ).toEqual({ type: "FIXED_VND", value: vnd(30000) });
  });

  it("kiểm tra giá trị và mô tả ngắn gọn", () => {
    expect(
      validateDiscount({ type: "FIXED_VND", percent: "", maxDiscount: undefined, fixed: 0 })
    ).not.toBeNull();
    expect(describeDiscount({ type: "FIXED_VND", value: vnd(30000) }, (n) => `${n}đ`)).toBe("Giảm 30000đ");
    expect(
      describeDiscount(
        { type: "PERCENTAGE", value_basis_points: 1250, max_discount: vnd(5000) },
        (n) => `${n}đ`
      )
    ).toBe("Giảm 12,5% (tối đa 5000đ)");
  });
});

const baseVoucher: Promotion = {
  id: "7",
  campaign_id: "3",
  campaign_revision: 4,
  code: "GIAM10",
  name: "Giảm 10%",
  description: null,
  distribution_mode: "PUBLIC_SAVE",
  discount: { type: "PERCENTAGE", value_basis_points: 1000, max_discount: null },
  min_order_amount: vnd(100000),
  conditions_rule: ALWAYS_TRUE_RULE,
  rule_schema_version: 1,
  validity: { mode: "CAMPAIGN_END" },
  is_enabled: true,
  budget: {
    total_claim_quantity: null,
    claimed_quantity: 0,
    total_redemption_quantity: 500,
    used_quantity: 12,
    total_budget: null,
    used_budget: vnd(0),
    updated_at: "2026-10-01T00:00:00Z",
  },
  created_at: "2026-10-01T00:00:00Z",
  updated_at: "2026-10-01T00:00:00Z",
};

describe("voucher", () => {
  it("dựng payload tạo đúng contract", () => {
    const draft = {
      ...emptyVoucherDraft(),
      code: "FREESHIP",
      name: " Miễn phí ",
      discount: { type: "FIXED_VND" as const, percent: "", maxDiscount: undefined, fixed: 20000 },
      minOrder: 0,
    };
    expect(validateVoucher(draft, { checkCode: true })).toEqual({ rule: [] });
    expect(voucherCreatePayload(draft)).toEqual({
      code: "FREESHIP",
      name: "Miễn phí",
      description: null,
      distribution_mode: "PUBLIC_SAVE",
      discount: { type: "FIXED_VND", value: vnd(20000) },
      min_order_amount: vnd(0),
      conditions_rule: ALWAYS_TRUE_RULE,
      rule_schema_version: 1,
      validity: { mode: "CAMPAIGN_END" },
      is_enabled: true,
      budget: { total_claim_quantity: null, total_redemption_quantity: null, total_budget: null },
    });
  });

  it("voucher số lượng giới hạn bắt buộc có số suất; chế độ khác luôn gửi null", () => {
    expect(
      validateBudget({ claimQuantity: undefined, redemptionQuantity: undefined, totalBudget: undefined }, "LIMITED_CLAIM")
    ).toContain("số suất nhận");
    const draft = {
      ...emptyVoucherDraft(),
      mode: "DIRECT_GRANT" as const,
      budget: { claimQuantity: 50, redemptionQuantity: undefined, totalBudget: undefined },
    };
    expect(voucherCreatePayload(draft).budget.total_claim_quantity).toBeNull();
  });

  it("kiểm tra mã, tên, mức giảm và hạn dùng", () => {
    const errors = validateVoucher(emptyVoucherDraft(), { checkCode: true });
    expect(errors.code).toBeDefined();
    expect(errors.name).toBeDefined();
    expect(errors.discount).toBeDefined();
    expect(
      validateValidity(
        { mode: "FIXED_DATE", duration: undefined, unit: "DAYS", redeemUntil: "2026-01-01T00:00:00Z" },
        "2026-02-01T00:00:00Z"
      )
    ).toContain("sau lúc campaign bắt đầu");
  });

  it("đổi khoảng thời gian dùng sang giây và ngược lại", () => {
    const validity = validityFromDraft({
      mode: "DURATION_AFTER_SAVE",
      duration: 7,
      unit: "DAYS",
      redeemUntil: undefined,
    });
    expect(validity).toEqual({ mode: "DURATION_AFTER_SAVE", valid_for_seconds: 604800 });
    expect(validityToDraft(validity)).toMatchObject({ duration: 7, unit: "DAYS" });
    expect(validityToDraft({ mode: "DURATION_AFTER_SAVE", valid_for_seconds: 5400 })).toMatchObject({
      duration: 90,
      unit: "MINUTES",
    });
  });

  it("merge-patch chỉ chứa trường đã đổi và gửi nguyên khối object lồng", () => {
    const draft = voucherToDraft(baseVoucher);
    expect(voucherPatch(baseVoucher, draft)).toBeNull();

    const changed = {
      ...draft,
      name: "Giảm 15%",
      discount: { ...draft.discount, percent: "15" },
      enabled: false,
    };
    expect(voucherPatch(baseVoucher, changed)).toEqual({
      name: "Giảm 15%",
      discount: { type: "PERCENTAGE", value_basis_points: 1500, max_discount: null },
      is_enabled: false,
    });
  });

  it("đổi rule kèm rule_schema_version; xoá mô tả gửi null", () => {
    const draft = voucherToDraft({ ...baseVoucher, description: "Cũ" });
    const patch = voucherPatch(
      { ...baseVoucher, description: "Cũ" },
      { ...draft, description: "", rule: { ...newCondition("cart.total_amount"), value: 300000 } }
    );
    expect(patch).toMatchObject({ description: null, rule_schema_version: 1 });
    expect(patch?.conditions_rule).toMatchObject({ field: "cart.total_amount", value: 300000 });
  });
});

describe("campaign", () => {
  const draft: CampaignDraft = {
    code: "SALE",
    name: "Sale",
    description: "",
    startTime: "2026-10-10T00:00:00.000Z",
    endTime: "2026-10-20T00:00:00.000Z",
  };

  it("chuẩn hoá mã thành chữ hoa và bỏ ký tự lạ", () => {
    expect(normalizeCode("sale 11.11_x-y")).toBe("SALE1111_X-Y");
  });

  it("yêu cầu lịch hợp lệ", () => {
    expect(validateCampaign(draft, { checkCode: true })).toEqual({});
    expect(
      validateCampaign({ ...draft, endTime: draft.startTime }, { checkCode: true }).schedule
    ).toBeDefined();
    expect(validateCampaign({ ...draft, code: "" }, { checkCode: true }).code).toBeDefined();
    expect(validateCampaign({ ...draft, code: "" }, { checkCode: false }).code).toBeUndefined();
  });

  it("patch chỉ gửi trường đổi và so sánh thời điểm theo instant", () => {
    const original = {
      id: "1",
      code: "SALE",
      name: "Sale",
      description: null,
      publication_status: "DRAFT" as const,
      phase: "DRAFT" as const,
      start_time: "2026-10-10T07:00:00+07:00",
      end_time: "2026-10-20T00:00:00Z",
      revision: 1,
      created_by: "a",
      updated_by: "a",
      created_at: "2026-10-01T00:00:00Z",
      updated_at: "2026-10-01T00:00:00Z",
    };
    expect(campaignPatch(original, draft)).toBeNull();
    expect(campaignPatch(original, { ...draft, name: "Sale lớn" })).toEqual({ name: "Sale lớn" });
  });
});

describe("markdown", () => {
  const rule: MarkdownRule = {
    id: "5",
    campaign_id: "3",
    campaign_revision: 2,
    product_id: UUID,
    discount: { type: "FIXED_VND", value: vnd(10000) },
    priority: 0,
    is_enabled: true,
    created_at: "2026-10-01T00:00:00Z",
    updated_at: "2026-10-01T00:00:00Z",
  };

  it("yêu cầu UUID sản phẩm khi tạo và bỏ qua khi sửa", () => {
    const draft = { ...markdownToDraft(rule), productId: "abc" };
    expect(validateMarkdown(draft, { checkProduct: true }).product).toBeDefined();
    expect(validateMarkdown(draft, { checkProduct: false })).toEqual({});
  });

  it("patch chỉ chứa trường đã đổi", () => {
    const draft = markdownToDraft(rule);
    expect(markdownPatch(rule, draft)).toBeNull();
    expect(markdownPatch(rule, { ...draft, priority: 5, enabled: false })).toEqual({
      priority: 5,
      is_enabled: false,
    });
  });
});
