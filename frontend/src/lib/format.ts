/**
 * Format a number as VND currency
 */
export function formatVND(amount: number): string {
  return new Intl.NumberFormat("vi-VN", {
    style: "currency",
    currency: "VND",
  }).format(amount);
}

/**
 * Format number with compact notation
 */
export function formatCompact(amount: number): string {
  if (amount >= 1_000_000) {
    return (amount / 1_000_000).toFixed(1).replace(/\.0$/, "") + "tr";
  }
  if (amount >= 1_000) {
    return (amount / 1_000).toFixed(0) + "k";
  }
  return String(amount);
}

/**
 * Format a timestamp string to human-readable date
 */
export function formatDate(timestamp: string): string {
  const ms = timestamp.length === 13 ? Number(timestamp) : Number(timestamp) * 1000;
  const date = new Date(ms);
  return date.toLocaleDateString("vi-VN", {
    year: "numeric",
    month: "long",
    day: "numeric",
  });
}

/**
 * Calculate discount percentage
 */
export function calcDiscount(original: number, best: number): number {
  if (original <= 0) return 0;
  return Math.round(((original - best) / original) * 100);
}
