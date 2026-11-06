import { describe, expect, it } from 'vitest'
import {
  COLS, GAP, ROW_H, applyMasonry, clampRect, colWidth, compact, freeSlot, gridToPx, overlaps, packMasonry,
  placeCard, pxToGrid, settle, totalRows, withRects
} from './biLayout'

describe('biLayout', () => {
  it('converts a rect to pixels with gap-aware width', () => {
    const width = 1200
    const cw = colWidth(width)
    const px = gridToPx({ x: 6, y: 2, w: 6, h: 8 }, width)
    // 返回带 px 的字符串：数字会被浏览器当成非法值丢掉
    expect(px.left).toBe(`${6 * (cw + GAP)}px`)
    expect(parseFloat(px.top)).toBeCloseTo(2 * (ROW_H + GAP))
    expect(parseFloat(px.width)).toBeCloseTo(6 * cw + 5 * GAP)
    expect(parseFloat(px.height)).toBeCloseTo(8 * ROW_H + 7 * GAP)
  })

  it('round-trips a pixel offset back to grid cells', () => {
    const width = 1200
    const cw = colWidth(width)
    const delta = pxToGrid(3 * (cw + GAP), 2 * (ROW_H + GAP), width)
    expect(delta).toEqual({ dx: 3, dy: 2 })
  })

  it('clamps rects inside the 12-column grid', () => {
    expect(clampRect({ x: 10, y: 0, w: 4, h: 8 })).toEqual({ x: 8, y: 0, w: 4, h: 8 })
    expect(clampRect({ x: -3, y: -2, w: 6, h: 8 })).toEqual({ x: 0, y: 0, w: 6, h: 8 })
    expect(clampRect({ x: 0, y: 0, w: 0, h: 999 }).w).toBe(2)
    expect(clampRect({ x: 0, y: 0, w: 99, h: 999 })).toEqual({ x: 0, y: 0, w: COLS, h: 40 })
  })

  it('detects overlap only when both axes intersect', () => {
    const a = { x: 0, y: 0, w: 6, h: 8 }
    expect(overlaps(a, { x: 5, y: 7, w: 2, h: 2 })).toBe(true)
    expect(overlaps(a, { x: 6, y: 0, w: 6, h: 8 })).toBe(false)
    expect(overlaps(a, { x: 0, y: 8, w: 6, h: 4 })).toBe(false)
  })

  it('finds a free slot on an empty canvas and around existing cards', () => {
    expect(freeSlot([], 6, 8)).toEqual({ x: 0, y: 0, w: 6, h: 8 })
    const taken = [{ x: 0, y: 0, w: 6, h: 8 }]
    expect(freeSlot(taken, 6, 8)).toEqual({ x: 6, y: 0, w: 6, h: 8 })
    const rowFull = [{ x: 0, y: 0, w: 12, h: 8 }]
    expect(freeSlot(rowFull, 6, 8)).toEqual({ x: 0, y: 8, w: 6, h: 8 })
  })

  it('packs cards into a masonry layout without overlaps', () => {
    // 两张半宽卡并排
    const pair = packMasonry([{ w: 6, h: 8 }, { w: 6, h: 8 }])
    expect(pair.map(item => [item.x, item.y])).toEqual([[0, 0], [6, 0]])

    // 第三张落在两列的下方
    const three = packMasonry([{ w: 6, h: 8 }, { w: 6, h: 8 }, { w: 6, h: 6 }])
    expect(three[2]).toMatchObject({ x: 0, y: 8 })

    // 整宽的卡另起一行
    const wide = packMasonry([{ w: 6, h: 8 }, { w: 6, h: 8 }, { w: 12, h: 4 }])
    expect(wide[2]).toMatchObject({ x: 0, y: 8 })
  })

  it('never produces overlapping rectangles', () => {
    const cards = [
      { w: 4, h: 6 }, { w: 8, h: 5 }, { w: 6, h: 7 }, { w: 6, h: 3 }, { w: 12, h: 4 }
    ]
    const packed = packMasonry(cards)
    packed.forEach((a, i) => {
      packed.slice(i + 1).forEach(b => {
        expect(overlaps(a, b)).toBe(false)
      })
    })
  })

  it('writes the packed coordinates back onto the cards', () => {
    const cards = [{ w: 6, h: 8 }, { w: 6, h: 8 }]
    applyMasonry(cards)
    expect(cards[0]).toMatchObject({ x: 0, y: 0 })
    expect(cards[1]).toMatchObject({ x: 6, y: 0 })
  })

  it('reports the rows the canvas must cover', () => {
    expect(totalRows([{ x: 0, y: 0, w: 6, h: 8 }, { x: 6, y: 4, w: 6, h: 10 }])).toBe(14)
    expect(totalRows([])).toBe(0)
  })

  it('lets a card sit anywhere in the row, not only the left edge', () => {
    const cards = [
      { x: 0, y: 0, w: 6, h: 8 },
      { x: 0, y: 8, w: 6, h: 8 }
    ]
    placeCard(cards, 1, { x: 6, y: 0, w: 6, h: 8 })
    expect(cards[1]).toMatchObject({ x: 6, y: 0 })
    expect(cards[0]).toMatchObject({ x: 0, y: 0 })
  })

  it('pushes the overlapped card down instead of stacking on top of it', () => {
    const cards = [
      { x: 0, y: 0, w: 6, h: 8 },
      { x: 6, y: 0, w: 6, h: 8 }
    ]
    placeCard(cards, 1, { x: 0, y: 0, w: 6, h: 8 })
    expect(cards[1]).toMatchObject({ x: 0, y: 0 })
    expect(cards[0]).toMatchObject({ x: 0, y: 8 })
    expect(overlaps(cards[0], cards[1])).toBe(false)
  })

  it('keeps cards inside the 12 columns when dragged past the right edge', () => {
    const cards = [{ x: 0, y: 0, w: 6, h: 8 }]
    placeCard(cards, 0, { x: 9, y: 0, w: 6, h: 8 })
    expect(cards[0]).toMatchObject({ x: 6, y: 0 })
  })

  it('floats a card up into the empty space above it', () => {
    const cards = [{ x: 6, y: 12, w: 6, h: 8 }]
    compact(cards)
    // 只动 y；x 和宽度是用户摆的，横向不补齐
    expect(cards[0]).toMatchObject({ x: 6, y: 0, w: 6, h: 8 })
  })

  it('stops right below the card above it in the same columns', () => {
    const cards = [
      { _k: 'a', x: 0, y: 0, w: 6, h: 4 },
      { _k: 'b', x: 0, y: 12, w: 6, h: 4 }
    ]
    compact(cards)
    expect(cards[1]).toMatchObject({ x: 0, y: 4 })
    expect(overlaps(cards[0], cards[1])).toBe(false)
  })

  it('compacts each column independently', () => {
    const cards = [
      { x: 0, y: 0, w: 6, h: 10 },
      { x: 6, y: 10, w: 6, h: 4 }
    ]
    compact(cards)
    // 右列上方是空的，补到 0；左列本来就在顶部
    expect(cards[1]).toMatchObject({ x: 6, y: 0 })
    expect(cards[0]).toMatchObject({ x: 0, y: 0 })
  })

  it('can leave the card being dragged where it is', () => {
    const cards = [
      { _k: 'drag', x: 0, y: 6, w: 6, h: 4 },
      { _k: 'other', x: 6, y: 20, w: 6, h: 4 }
    ]
    compact(cards, 'drag')
    // 被拖的那张跟着指针，先不动；别的卡照常往上补
    expect(cards[0]).toMatchObject({ y: 6 })
    expect(cards[1]).toMatchObject({ y: 0 })
  })

  it('settles overlaps before saving and fills in missing coordinates', () => {
    const cards = [
      { x: 0, y: 0, w: 6, h: 8 },
      { x: 0, y: 0, w: 6, h: 8 }
    ]
    settle(cards)
    expect(overlaps(cards[0], cards[1])).toBe(false)

    const legacy = [{ w: 6, h: 8 }, { w: 6, h: 8 }]
    withRects(legacy)
    expect(legacy[0]).toMatchObject({ x: 0, y: 0 })
    expect(legacy[1]).toMatchObject({ x: 6, y: 0 })
  })
})
