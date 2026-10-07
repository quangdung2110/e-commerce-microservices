/**
 * Phân trang cursor theo quy ước `contracts/conventions/http.md`: response có
 * `next_cursor` khi còn trang sau, vắng mặt khi đã hết. Cursor là chuỗi mờ — không
 * parse, không tự dựng.
 */

export interface CursorPage {
  next_cursor?: string | null;
}

/** Cursor của trang kế tiếp, `undefined` khi đã hết (đúng quy ước của React Query). */
export function nextCursorOf(page: CursorPage): string | undefined {
  return page.next_cursor ?? undefined;
}

/**
 * Trạng thái phân trang trước/sau cho bảng admin.
 *
 * API chỉ cho đi tiếp, nên muốn quay lại phải nhớ cursor của các trang đã qua.
 * `current` là cursor của trang đang xem (`null` = trang đầu), `history` là
 * cursor của các trang trước đó theo thứ tự.
 */
export interface CursorPagerState {
  current: string | null;
  history: (string | null)[];
}

export const FIRST_PAGE: CursorPagerState = { current: null, history: [] };

export type CursorPagerAction =
  | { type: "next"; cursor: string }
  | { type: "previous" }
  | { type: "reset" };

export function cursorPagerReducer(
  state: CursorPagerState,
  action: CursorPagerAction
): CursorPagerState {
  switch (action.type) {
    case "next":
      return { current: action.cursor, history: [...state.history, state.current] };
    case "previous": {
      if (state.history.length === 0) return FIRST_PAGE;
      const history = state.history.slice(0, -1);
      return { current: state.history[state.history.length - 1], history };
    }
    case "reset":
      return FIRST_PAGE;
  }
}

/** Số thứ tự trang hiện tại, bắt đầu từ 1. */
export function pageNumber(state: CursorPagerState): number {
  return state.history.length + 1;
}
