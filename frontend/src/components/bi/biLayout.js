/**
 * 12 列网格的布局数学。
 *
 * <p>卡片的 x / y / w / h 都是「格」，不是像素：改行高不会让已存的布局失效，
 * 也让后端能校验「必须落在 12 列内」。这里只做换算与夹取，不碰 DOM、不碰响应式。
 */
export const COLS = 12
export const ROW_H = 40
export const GAP = 12
export const MIN_W = 2
export const MIN_H = 4
export const MAX_H = 40

/** 容器没量到宽度时（jsdom、隐藏 tab）用的兜底宽度，保证换算仍然可算。 */
const FALLBACK_WIDTH = 1200

export function colWidth(containerWidth) {
  const width = Number(containerWidth) > 0 ? Number(containerWidth) : FALLBACK_WIDTH
  return (width - GAP * (COLS - 1)) / COLS
}

/**
 * 返回的是**带 px 的字符串**，不是数字。
 *
 * <p>Vue 3.5 的 style binding 对数字原样透传，浏览器会当成非法值丢掉——
 * 表现就是「卡片拿到了样式对象却完全没尺寸」，很难查。
 */
export function gridToPx(rect, width) {
  const cw = colWidth(width)
  const w = clamp(rect.w, MIN_W, COLS)
  const h = clamp(rect.h, MIN_H, MAX_H)
  return {
    left: `${clamp(rect.x, 0, COLS - w) * (cw + GAP)}px`,
    top: `${Math.max(0, rect.y) * (ROW_H + GAP)}px`,
    width: `${w * cw + (w - 1) * GAP}px`,
    height: `${h * ROW_H + (h - 1) * GAP}px`
  }
}

/** 像素位移 → 格数位移（四舍五入，拖到一半就算一格）。 */
export function pxToGrid(dx, dy, width) {
  const cw = colWidth(width)
  return { dx: Math.round(dx / (cw + GAP)), dy: Math.round(dy / (ROW_H + GAP)) }
}

/** 夹到合法范围：x 不小于 0，x+w 不超过 12，高度不超过 40。 */
export function clampRect(rect) {
  const w = clamp(Math.round(rect.w), MIN_W, COLS)
  const h = clamp(Math.round(rect.h), MIN_H, MAX_H)
  return {
    x: clamp(Math.round(rect.x), 0, COLS - w),
    y: Math.max(0, Math.round(rect.y)),
    w,
    h
  }
}

export function overlaps(a, b) {
  return a.x < b.x + b.w && b.x < a.x + a.w && a.y < b.y + b.h && b.y < a.y + a.h
}

/**
 * 瀑布流排布：卡片只决定宽度（占几列）和高度（几行），位置由这里算。
 *
 * <p>从 12 列出发，每张卡放在「顶部最靠上、同高度时最靠左」的空位（skyline 算法），
 * 所以永远不会重叠，也不需要用户去对格子。返回的是带 x/y 的副本，不改原对象。
 */
export function packMasonry(cards, cols = COLS) {
  const heights = new Array(cols).fill(0)
  return (cards || []).map(card => {
    const w = clamp(Math.round(card.w), MIN_W, cols)
    const h = clamp(Math.round(card.h), MIN_H, MAX_H)
    let bestX = 0
    let bestY = Infinity
    for (let x = 0; x + w <= cols; x++) {
      let y = 0
      for (let i = x; i < x + w; i++) y = Math.max(y, heights[i])
      if (y < bestY) {
        bestY = y
        bestX = x
      }
    }
    for (let i = bestX; i < bestX + w; i++) heights[i] = bestY + h
    return { ...card, x: bestX, y: bestY, w, h }
  })
}

/** 把排布结果写回卡片（保存前调用，保证存下来的是不重叠的坐标）。 */
export function applyMasonry(cards, cols = COLS) {
  const packed = packMasonry(cards, cols)
  packed.forEach((item, index) => {
    if (cards[index]) {
      cards[index].x = item.x
      cards[index].y = item.y
      cards[index].w = item.w
      cards[index].h = item.h
    }
  })
  return cards
}

/** 从第 0 行开始逐行扫描，找第一个不与现有卡片重叠的位置。 */
export function freeSlot(cards, w = 6, h = 8) {
  const width = clamp(w, MIN_W, COLS)
  const height = clamp(h, MIN_H, MAX_H)
  const occupied = (cards || []).map(card => ({ x: card.x, y: card.y, w: card.w, h: card.h }))
  for (let y = 0; y < 400; y++) {
    for (let x = 0; x + width <= COLS; x++) {
      const candidate = { x, y, w: width, h: height }
      if (!occupied.some(item => overlaps(item, candidate))) return candidate
    }
  }
  // 实在放不下就压在最后一行，至少不会丢
  const bottom = occupied.reduce((max, item) => Math.max(max, item.y + item.h), 0)
  return { x: 0, y: bottom, w: width, h: height }
}

/** 所有卡片占用的总行数，用来给画布撑高度。 */
export function totalRows(cards) {
  return (cards || []).reduce((max, card) => Math.max(max, card.y + card.h), 0)
}

function clamp(value, min, max) {
  const number = Number.isFinite(Number(value)) ? Number(value) : min
  return Math.min(Math.max(number, min), max)
}
