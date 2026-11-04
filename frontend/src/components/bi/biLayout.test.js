import { describe, expect, it } from 'vitest'
import {
  COLS, GAP, ROW_H, applyMasonry, clampRect, colWidth, freeSlot, gridToPx, overlaps, packMasonry, pxToGrid, totalRows
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
})
