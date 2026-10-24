// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { act, cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";

const router = vi.hoisted(() => ({ push: vi.fn(), replace: vi.fn() }));
vi.mock("next/navigation", () => ({
  useRouter: () => router,
  usePathname: () => "/reviews",
}));
vi.mock("@/lib/api/review", () => ({
  reviewApi: {
    listPending: vi.fn(),
    listMine: vi.fn(),
    mine: vi.fn(),
    eligibilityStatus: vi.fn(),
    create: vi.fn(),
    patch: vi.fn(),
    issueUpload: vi.fn(),
  },
}));

import { ReviewCreateContent } from "@/components/reviews/review-create-content";
import { ReviewDetailContent } from "@/components/reviews/review-detail-content";
import { ReviewsPageContent } from "@/components/reviews/reviews-page-content";
import { ToastProvider } from "@/components/ui/toast";
import { httpError, problem } from "@/lib/api/__tests__/helpers";
import { reviewApi, type OwnerReview, type PendingEligibility } from "@/lib/api/review";
import { useAuthStore } from "@/store/useAuthStore";
import { formatDateTime } from "@/lib/format";

const pending = (overrides: Partial<PendingEligibility> = {}): PendingEligibility => ({
  eligibility_id: "e-1",
  order_id: "1000201",
  product_id: "p-1",
  sku_id: "sku-1",
  variant_name: "Đen / L",
  product_name: "Áo thun cổ tròn Terra",
  product_image_url: null,
  expires_at: new Date(Date.now() + 20 * 86_400_000).toISOString(),
  ...overrides,
});

const owner = (overrides: Partial<OwnerReview> = {}): OwnerReview => ({
  review_id: "r-1",
  product_id: "p-1",
  sku_id: "sku-1",
  author_name: "Khách hàng #AAAA",
  variant_name: "Đen / L",
  product_name: "Áo thun cổ tròn Terra",
  product_image_url: null,
  rating: 5,
  content: "Rất tốt",
  has_media: false,
  media: [],
  admin_reply: null,
  visibility_status: "APPROVED",
  edit_count: 0,
  created_at: "2026-10-06T00:00:00Z",
  updated_at: "2026-10-06T00:00:00Z",
  content_edited_at: null,
  ...overrides,
});

function login() {
  useAuthStore.getState().setSession(`e30.${btoa(JSON.stringify({ sub: "buyer-a", roles: ["USER"] }))}.test`);
}

function renderWithClient(ui: React.ReactElement) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  return render(<QueryClientProvider client={client}><ToastProvider>{ui}</ToastProvider></QueryClientProvider>);
}

beforeEach(() => {
  vi.resetAllMocks();
  login();
});
afterEach(cleanup);

describe("/reviews", () => {
  it("hiện tên, biến thể, hạn và link viết đánh giá của từng sản phẩm chờ", async () => {
    vi.mocked(reviewApi.listPending).mockResolvedValue({ items: [pending()], next_cursor: null });
    renderWithClient(<ReviewsPageContent tab="pending" />);

    expect(await screen.findByText("Áo thun cổ tròn Terra")).toBeTruthy();
    expect(screen.getByText("Đen / L")).toBeTruthy();
    expect(screen.getByText(/Còn 19 ngày|Còn 20 ngày/)).toBeTruthy();
    expect(screen.getByRole("link", { name: "Viết đánh giá" }).getAttribute("href")).toBe("/reviews/new?eligibility=e-1");
    expect(screen.getByRole("link", { name: "Đơn #1000201" }).getAttribute("href")).toBe("/orders/1000201");
  });

  it("entitlement cũ chưa có snapshot vẫn hiển thị câu thay thế", async () => {
    vi.mocked(reviewApi.listPending).mockResolvedValue({
      items: [pending({ product_name: null, product_image_url: null })],
      next_cursor: null,
    });
    renderWithClient(<ReviewsPageContent tab="pending" />);
    expect(await screen.findByText("Sản phẩm đã mua")).toBeTruthy();
  });

  it("danh sách rỗng có câu giải thích và lối thoát", async () => {
    vi.mocked(reviewApi.listPending).mockResolvedValue({ items: [], next_cursor: null });
    renderWithClient(<ReviewsPageContent tab="pending" />);
    expect(await screen.findByText("Bạn không có sản phẩm nào chờ đánh giá")).toBeTruthy();
    expect(screen.getByRole("link", { name: "Xem đơn hàng" })).toBeTruthy();
  });

  it("lỗi tải không lộ detail của backend và cho thử lại", async () => {
    vi.mocked(reviewApi.listPending).mockRejectedValue(httpError(503, { ...problem("REVIEW_STORE_UNAVAILABLE", 503), detail: "SQLSTATE 57P01 connection lost" }));
    renderWithClient(<ReviewsPageContent tab="pending" />);
    expect(await screen.findByText("Hệ thống đánh giá đang bận. Vui lòng thử lại sau ít phút.")).toBeTruthy();
    expect(screen.queryByText(/SQLSTATE/)).toBeNull();
    expect(screen.getByRole("button", { name: /Thử lại/ })).toBeTruthy();
  });

  it("tab đã viết liệt kê cả review bị ẩn kèm nhãn trạng thái", async () => {
    vi.mocked(reviewApi.listMine).mockResolvedValue({
      items: [owner(), owner({ review_id: "r-2", visibility_status: "HIDDEN_BY_REPORT", admin_reply: { content: "ok", created_at: "2026-10-06T00:00:00Z", updated_at: "2026-10-06T00:00:00Z" } })],
      next_cursor: null,
    });
    renderWithClient(<ReviewsPageContent tab="written" />);

    expect(await screen.findByText("Đang hiển thị")).toBeTruthy();
    expect(screen.getByText("Đang bị ẩn")).toBeTruthy();
    expect(screen.getByText("Cửa hàng đã phản hồi")).toBeTruthy();
    expect(screen.getAllByRole("link", { name: /Xem chi tiết/ })[1].getAttribute("href")).toBe("/reviews/r-2");
  });
});

describe("/reviews/new", () => {
  const pendingStatus = { eligibility_id: "e-1", status: "PENDING" as const, expires_at: new Date(Date.now() + 5 * 86_400_000).toISOString(), review_id: null };

  beforeEach(() => {
    vi.mocked(reviewApi.eligibilityStatus).mockResolvedValue(pendingStatus);
    vi.mocked(reviewApi.listPending).mockResolvedValue({ items: [pending()], next_cursor: null });
  });

  it("trong lúc tìm tên sản phẩm chỉ hiện skeleton, không nháy câu thay thế", async () => {
    vi.mocked(reviewApi.listPending).mockReturnValue(new Promise(() => undefined));
    renderWithClient(<ReviewCreateContent eligibilityId="e-1" />);

    expect(await screen.findByLabelText("Đang tải thông tin sản phẩm")).toBeTruthy();
    expect(screen.queryByText("Sản phẩm đã mua")).toBeNull();
  });

  it("không gửi khi chưa chọn sao và gắn lỗi vào nhóm sao", async () => {
    renderWithClient(<ReviewCreateContent eligibilityId="e-1" />);
    await screen.findByText("Áo thun cổ tròn Terra");

    fireEvent.click(screen.getByRole("button", { name: "Gửi đánh giá" }));

    expect((await screen.findByText("Hãy chọn số sao.")).getAttribute("role")).toBe("alert");
    expect(screen.getByRole("radiogroup").getAttribute("aria-invalid")).toBe("true");
    expect(reviewApi.create).not.toHaveBeenCalled();
  });

  it("gửi đúng body (cắt khoảng trắng, không gửi media rỗng) rồi chuyển tới review vừa tạo", async () => {
    vi.mocked(reviewApi.create).mockResolvedValue(owner({ review_id: "r-new" }));
    renderWithClient(<ReviewCreateContent eligibilityId="e-1" />);
    await screen.findByText("Áo thun cổ tròn Terra");

    fireEvent.click(screen.getByRole("radio", { name: "4 sao, Tốt" }));
    fireEvent.change(screen.getByLabelText(/Nhận xét/), { target: { value: "  Dùng ổn  " } });
    fireEvent.click(screen.getByRole("button", { name: "Gửi đánh giá" }));

    await waitFor(() => expect(reviewApi.create).toHaveBeenCalledTimes(1));
    expect(reviewApi.create).toHaveBeenCalledWith({ eligibility_id: "e-1", rating: 4, content: "Dùng ổn" });
    await waitFor(() => expect(router.replace).toHaveBeenCalledWith("/reviews/r-new"));
  });

  it("nội dung trống chỉ gửi điểm sao", async () => {
    vi.mocked(reviewApi.create).mockResolvedValue(owner());
    renderWithClient(<ReviewCreateContent eligibilityId="e-1" />);
    await screen.findByText("Áo thun cổ tròn Terra");

    fireEvent.click(screen.getByRole("radio", { name: "5 sao, Rất tốt" }));
    fireEvent.change(screen.getByLabelText(/Nhận xét/), { target: { value: "   " } });
    fireEvent.click(screen.getByRole("button", { name: "Gửi đánh giá" }));

    await waitFor(() => expect(reviewApi.create).toHaveBeenCalledWith({ eligibility_id: "e-1", rating: 5 }));
  });

  it("hiện toast và điều hướng dù refetch entitlement trả CLAIMED ngay sau create", async () => {
    vi.mocked(reviewApi.create).mockImplementation(async () => {
      vi.mocked(reviewApi.eligibilityStatus).mockResolvedValue({ ...pendingStatus, status: "CLAIMED", review_id: "r-new" });
      // Leave another invalidation in flight so the parent has time to remove the form.
      vi.mocked(reviewApi.listPending).mockImplementation(() => new Promise(resolve =>
        setTimeout(() => resolve({ items: [], next_cursor: null }), 30)));
      return owner({ review_id: "r-new" });
    });
    renderWithClient(<ReviewCreateContent eligibilityId="e-1" />);
    await screen.findByText("Áo thun cổ tròn Terra");
    fireEvent.click(screen.getByRole("radio", { name: "5 sao, Rất tốt" }));
    fireEvent.click(screen.getByRole("button", { name: "Gửi đánh giá" }));
    await waitFor(() => expect(router.replace).toHaveBeenCalledExactlyOnceWith("/reviews/r-new"));
    expect(await screen.findByText("Đã gửi đánh giá")).toBeTruthy();
    await screen.findByText("Bạn đã đánh giá sản phẩm này");
    expect(reviewApi.create).toHaveBeenCalledTimes(1);
  });

  it("không điều hướng hoặc toast khi đã rời form trước response create", async () => {
    let resolve!: (saved: OwnerReview) => void;
    vi.mocked(reviewApi.create).mockReturnValue(new Promise(r => { resolve = r; }));
    const view = renderWithClient(<ReviewCreateContent eligibilityId="e-1" />);
    await screen.findByText("Áo thun cổ tròn Terra");
    fireEvent.click(screen.getByRole("radio", { name: "5 sao, Rất tốt" }));
    fireEvent.click(screen.getByRole("button", { name: "Gửi đánh giá" }));
    await waitFor(() => expect(reviewApi.create).toHaveBeenCalledTimes(1));
    view.unmount();
    resolve(owner());
    await new Promise(r => setTimeout(r, 40));
    expect(router.replace).not.toHaveBeenCalled();
  });

  it("response của account trước không điều hướng hoặc toast cho account mới", async () => {
    let resolve!: (saved: OwnerReview) => void;
    vi.mocked(reviewApi.create).mockReturnValue(new Promise(r => { resolve = r; }));
    renderWithClient(<ReviewCreateContent eligibilityId="e-1" />);
    await screen.findByText("Áo thun cổ tròn Terra");
    fireEvent.click(screen.getByRole("radio", { name: "5 sao, Rất tốt" }));
    fireEvent.click(screen.getByRole("button", { name: "Gửi đánh giá" }));
    await waitFor(() => expect(reviewApi.create).toHaveBeenCalledTimes(1));
    await act(async () => {
      useAuthStore.getState().setSession(`e30.${btoa(JSON.stringify({ sub: "buyer-b", roles: ["USER"] }))}.test`);
      await new Promise(r => setTimeout(r, 0));
      resolve(owner());
      await new Promise(r => setTimeout(r, 40));
    });
    expect(router.replace).not.toHaveBeenCalled();
    expect(screen.queryByText("Đã gửi đánh giá")).toBeNull();
  });

  it("eligibility đã CLAIMED thì không hiện form mà dẫn tới review đã viết", async () => {
    vi.mocked(reviewApi.eligibilityStatus).mockResolvedValue({ ...pendingStatus, status: "CLAIMED", review_id: "r-9" });
    renderWithClient(<ReviewCreateContent eligibilityId="e-1" />);

    expect(await screen.findByText("Bạn đã đánh giá sản phẩm này")).toBeTruthy();
    expect(screen.getByRole("link", { name: "Xem đánh giá của bạn" }).getAttribute("href")).toBe("/reviews/r-9");
    expect(screen.queryByRole("button", { name: "Gửi đánh giá" })).toBeNull();
  });

  it("eligibility hết hạn thì báo rõ và không hiện form", async () => {
    vi.mocked(reviewApi.eligibilityStatus).mockResolvedValue({ ...pendingStatus, status: "EXPIRED" });
    renderWithClient(<ReviewCreateContent eligibilityId="e-1" />);
    expect(await screen.findByText("Đã hết thời hạn đánh giá")).toBeTruthy();
    expect(screen.queryByRole("button", { name: "Gửi đánh giá" })).toBeNull();
  });

  it("eligibility của người khác (403) là lỗi dứt khoát, không có nút thử lại", async () => {
    vi.mocked(reviewApi.eligibilityStatus).mockRejectedValue(httpError(403, problem("NOT_ELIGIBILITY_OWNER", 403)));
    renderWithClient(<ReviewCreateContent eligibilityId="e-1" />);
    expect(await screen.findByText("Bạn không có quyền đánh giá cho đơn hàng này.")).toBeTruthy();
    expect(screen.queryByRole("button", { name: /Thử lại/ })).toBeNull();
  });
});

describe("/reviews/[id]", () => {
  it("giữ đúng thời gian tác giả sửa khi moderation thay updated_at", async () => {
    const editedAt = "2026-10-13T14:38:27Z";
    vi.mocked(reviewApi.mine).mockResolvedValue(owner({
      edit_count: 1, content_edited_at: editedAt,
      updated_at: "2026-10-14T04:17:11Z", visibility_status: "REMOVED",
    }));
    renderWithClient(<ReviewDetailContent reviewId="r-1" />);
    expect(await screen.findByText(new RegExp(`Đã chỉnh sửa lúc ${formatDateTime(editedAt)}`))).toBeTruthy();
    expect(screen.queryByText(new RegExp(`Đã chỉnh sửa lúc ${formatDateTime("2026-10-14T04:17:11Z")}`))).toBeNull();
  });

  it.each([null, undefined])("review cũ thiếu giờ sửa (%s) không lấy updated_at thay thế", async (timestamp) => {
    vi.mocked(reviewApi.mine).mockResolvedValue(owner({ edit_count: 1, content_edited_at: timestamp }));
    renderWithClient(<ReviewDetailContent reviewId="r-1" />);
    expect(await screen.findByText(/Đã chỉnh sửa/)).toBeTruthy();
    expect(screen.queryByText(/Đã chỉnh sửa lúc/)).toBeNull();
  });

  it("review còn sửa được hiện nút chỉnh sửa và số lượt còn lại", async () => {
    vi.mocked(reviewApi.mine).mockResolvedValue(owner());
    renderWithClient(<ReviewDetailContent reviewId="r-1" />);
    expect(await screen.findByText("Bạn còn 1 lần chỉnh sửa.")).toBeTruthy();
    expect(screen.getByRole("button", { name: "Chỉnh sửa" })).toBeTruthy();
  });

  it("đã dùng lượt sửa thì không còn nút chỉnh sửa", async () => {
    vi.mocked(reviewApi.mine).mockResolvedValue(owner({ edit_count: 1 }));
    renderWithClient(<ReviewDetailContent reviewId="r-1" />);
    expect(await screen.findByText("Bạn đã dùng lượt chỉnh sửa duy nhất.")).toBeTruthy();
    expect(screen.queryByRole("button", { name: "Chỉnh sửa" })).toBeNull();
  });

  it("review bị ẩn có giải thích, không có nút sửa và không hiện media", async () => {
    vi.mocked(reviewApi.mine).mockResolvedValue(owner({ visibility_status: "HIDDEN_BY_REPORT", has_media: true, media: [] }));
    renderWithClient(<ReviewDetailContent reviewId="r-1" />);
    expect(await screen.findByText(/tạm thời không hiển thị công khai/)).toBeTruthy();
    expect(screen.queryByRole("button", { name: "Chỉnh sửa" })).toBeNull();
    expect(screen.getByText(/Ảnh và video không hiển thị/)).toBeTruthy();
  });

  it("chỉ gửi trường đã đổi và hỏi xác nhận vì chỉ sửa được một lần", async () => {
    vi.mocked(reviewApi.mine).mockResolvedValue(owner());
    vi.mocked(reviewApi.patch).mockResolvedValue(owner({ rating: 3, edit_count: 1 }));
    renderWithClient(<ReviewDetailContent reviewId="r-1" />);

    fireEvent.click(await screen.findByRole("button", { name: "Chỉnh sửa" }));
    const save = screen.getByRole("button", { name: "Lưu thay đổi" }) as HTMLButtonElement;
    expect(save.disabled).toBe(true);

    fireEvent.click(screen.getByRole("radio", { name: "3 sao, Bình thường" }));
    fireEvent.click(screen.getByRole("button", { name: "Lưu thay đổi" }));
    expect(await screen.findByText("Mỗi đánh giá chỉ được chỉnh sửa một lần. Sau khi lưu bạn sẽ không sửa được nữa.")).toBeTruthy();
    expect(reviewApi.patch).not.toHaveBeenCalled();

    fireEvent.click(screen.getByRole("button", { name: "Lưu chỉnh sửa" }));
    await waitFor(() => expect(reviewApi.patch).toHaveBeenCalledWith("r-1", { rating: 3 }));
  });

  it("xoá nội dung gửi content: null, còn media thì không đụng tới", async () => {
    vi.mocked(reviewApi.mine).mockResolvedValue(owner());
    vi.mocked(reviewApi.patch).mockResolvedValue(owner({ content: null, edit_count: 1 }));
    renderWithClient(<ReviewDetailContent reviewId="r-1" />);

    fireEvent.click(await screen.findByRole("button", { name: "Chỉnh sửa" }));
    fireEvent.change(screen.getByLabelText(/Nhận xét/), { target: { value: "" } });
    fireEvent.click(screen.getByRole("button", { name: "Lưu thay đổi" }));
    fireEvent.click(await screen.findByRole("button", { name: "Lưu chỉnh sửa" }));

    await waitFor(() => expect(reviewApi.patch).toHaveBeenCalledWith("r-1", { content: null }));
  });

  it("review của người khác (403) không có nút thử lại", async () => {
    vi.mocked(reviewApi.mine).mockRejectedValue(httpError(403, problem("NOT_REVIEW_OWNER", 403)));
    renderWithClient(<ReviewDetailContent reviewId="r-1" />);
    expect(await screen.findByText("Bạn không có quyền xem đánh giá này.")).toBeTruthy();
    expect(screen.queryByRole("button", { name: /Thử lại/ })).toBeNull();
  });
});
