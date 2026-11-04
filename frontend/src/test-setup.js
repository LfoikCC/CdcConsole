/**
 * jsdom 里没有 ResizeObserver，而 chartStyle.createChart 一上来就 new 一个。
 * 不补这个，任何渲染图表的组件在单测里都会直接抛错。
 */
if (typeof globalThis.ResizeObserver === 'undefined') {
  globalThis.ResizeObserver = class {
    observe() {}
    unobserve() {}
    disconnect() {}
  }
}
