"use client";

import { useCallback, useReducer } from "react";
import {
  useInfiniteQuery,
  useQuery,
  type QueryKey,
} from "@tanstack/react-query";

import {
  FIRST_PAGE,
  cursorPagerReducer,
  nextCursorOf,
  pageNumber,
  type CursorPage,
  type CursorPagerAction,
  type CursorPagerState,
} from "@/lib/api/pagination";

interface CursorListOptions<TPage extends CursorPage> {
  /** Key gốc, đã gồm mọi bộ lọc. Đổi bộ lọc = đổi key = về trang đầu. */
  queryKey: QueryKey;
  fetchPage: (cursor: string | undefined, signal: AbortSignal) => Promise<TPage>;
  enabled?: boolean;
}

interface CursorInfiniteListOptions<TPage extends CursorPage> extends CursorListOptions<TPage> {
  initialPage?: TPage;
  refetchOnMount?: boolean;
  refetchOnWindowFocus?: boolean;
  staleTime?: number;
  gcTime?: number;
}

/**
 * Danh sách "Tải thêm" cho storefront: các trang nối tiếp nhau trên cùng màn hình.
 */
export function useCursorInfiniteList<TPage extends CursorPage>({
  queryKey,
  fetchPage,
  enabled = true,
  initialPage,
  ...queryOptions
}: CursorInfiniteListOptions<TPage>) {
  return useInfiniteQuery({
    queryKey,
    queryFn: ({ pageParam, signal }) => fetchPage(pageParam, signal),
    initialPageParam: undefined as string | undefined,
    getNextPageParam: (lastPage) => nextCursorOf(lastPage),
    initialData: initialPage ? { pages: [initialPage], pageParams: [undefined] } : undefined,
    enabled,
    ...queryOptions,
  });
}

interface KeyedPagerState {
  key: string;
  pager: CursorPagerState;
}

type KeyedPagerAction = CursorPagerAction & { key: string };

/** Lịch sử cursor chỉ có nghĩa với đúng bộ lọc đã tạo ra nó. */
function keyedPagerReducer(state: KeyedPagerState, action: KeyedPagerAction): KeyedPagerState {
  const base = state.key === action.key ? state.pager : FIRST_PAGE;
  return { key: action.key, pager: cursorPagerReducer(base, action) };
}

/**
 * Bảng admin: mỗi lần chỉ hiện một trang, có nút trước/sau.
 *
 * Lịch sử cursor nằm trong state của component, không trên URL — chuỗi cursor
 * dài và mờ, đưa lên URL chỉ làm link khó chia sẻ. Bộ lọc thì nằm trên URL và
 * là một phần của `queryKey`; khi `queryKey` đổi, pager tự về trang đầu.
 */
export function useCursorPager<TPage extends CursorPage>({
  queryKey,
  fetchPage,
  enabled = true,
}: CursorListOptions<TPage>) {
  const key = JSON.stringify(queryKey);
  const [keyed, dispatchKeyed] = useReducer(keyedPagerReducer, {
    key,
    pager: FIRST_PAGE,
  });
  // Bộ lọc vừa đổi mà chưa có thao tác nào: coi như đang ở trang đầu.
  const state = keyed.key === key ? keyed.pager : FIRST_PAGE;
  const dispatch = useCallback(
    (action: CursorPagerAction) => dispatchKeyed({ ...action, key }),
    [key]
  );

  const query = useQuery({
    queryKey: [...queryKey, { cursor: state.current }],
    queryFn: ({ signal }) => fetchPage(state.current ?? undefined, signal),
    enabled,
    // Giữ trang cũ trên màn hình trong lúc tải trang mới, tránh bảng nháy trắng.
    placeholderData: (previous) => previous,
  });

  const nextCursor = query.data ? nextCursorOf(query.data) : undefined;

  const goNext = useCallback(() => {
    if (nextCursor) dispatch({ type: "next", cursor: nextCursor });
  }, [dispatch, nextCursor]);
  const goPrevious = useCallback(() => dispatch({ type: "previous" }), [dispatch]);
  const reset = useCallback(() => dispatch({ type: "reset" }), [dispatch]);

  return {
    ...query,
    page: pageNumber(state),
    hasNext: nextCursor !== undefined,
    hasPrevious: state.history.length > 0,
    goNext,
    goPrevious,
    reset,
  };
}
