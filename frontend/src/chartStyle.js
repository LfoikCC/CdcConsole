import * as echarts from 'echarts'

/**
 * 默认用 SVG：文字是矢量，任何缩放/高倍屏下都锐利。
 *
 * <p>Canvas 在高 DPI 屏上受像素密度限制，11px 的小字会发虚（尤其轴刻度和数据标签）。
 * 数据点特别多的图由 BiChart 按行数切到 Canvas，见 HEAVY_ROWS。
 */
const DEFAULT_RENDERER = 'svg'

export function createChart(element, renderer = DEFAULT_RENDERER) {
  const chart = echarts.init(element, null, {
    renderer,
    // 走 Canvas 兜底时按真实像素密度画，封顶 3 倍（再高收益很小、代价明显）
    devicePixelRatio: Math.min(window.devicePixelRatio || 1, 3)
  })
  const observer = new ResizeObserver(() => { if (!chart.isDisposed()) chart.resize() })
  observer.observe(element)
  const dispose = chart.dispose.bind(chart)
  chart.dispose = () => { observer.disconnect(); dispose() }
  return chart
}

// ---------------------------------------------------------------- 主题变量

let cachedTheme = null
let cachedVars = null

/**
 * 主题色只在切主题时读一次。
 *
 * <p>`getComputedStyle` 会强制浏览器重算样式，而图表每次重绘都要一套颜色；
 * 之前一次 draw 要调 3 次，动画期间等于每帧都在做样式计算。
 */
function themeVars() {
  const theme = document.documentElement.dataset.theme || 'light'
  if (cachedVars && cachedTheme === theme) return cachedVars
  const css = getComputedStyle(document.documentElement)
  const color = name => css.getPropertyValue(name).trim()
  cachedTheme = theme
  cachedVars = {
    muted: color('--muted-foreground'),
    border: color('--border'),
    card: color('--card'),
    foreground: color('--foreground'),
    fontFamily: getComputedStyle(document.body).fontFamily,
    palette: ['--info', '--success', '--warning', '--purple', '--danger'].map(color)
  }
  return cachedVars
}

/** 主题被外部改过（例如测试里直接改 dataset）时手动失效。 */
export function invalidateChartStyle() {
  cachedTheme = null
  cachedVars = null
}

export function chartStyle() {
  const vars = themeVars()
  const text = { fontFamily: vars.fontFamily, fontSize: 12, color: vars.muted }
  return {
    // 默认开动画：柱状从底部长出来、折线从左往右画，切换图表类型/刷新数据都不会「啪」地换掉
    animation: true,
    animationDuration: 420,
    animationEasing: 'cubicOut',
    animationDurationUpdate: 320,
    animationEasingUpdate: 'cubicOut',
    backgroundColor: 'transparent',
    // 复制一份：调用方可能改这个数组，缓存里的原始值不能被动到
    color: [...vars.palette],
    textStyle: { ...text, color: vars.foreground },
    tooltip: { trigger: 'axis', renderMode: 'html', confine: true,
      // 挂到 body：留在图表容器里会继承卡片的合成层与 overflow，
      // 文字被当成贴图缩放，看着就是糊的
      appendToBody: true,
      backgroundColor: vars.card, borderColor: vars.border, padding: [10, 12],
      // 13px 起读；12px 在 1.25/1.5 倍缩放的屏上已经开始掉笔画
      textStyle: { ...text, fontSize: 13, color: vars.foreground },
      extraCssText: 'border-radius:6px;box-shadow:none;line-height:1.7;'
        // ECharts 默认给 tooltip 加 will-change:transform 走 GPU 合成，
        // 合成层里的文字不用次像素抗锯齿（Windows 的 ClearType），必然发虚
        + 'will-change:auto;'
        // body 上是 antialiased，Windows 下等于关掉 ClearType，这里改回次像素
        + '-webkit-font-smoothing:subpixel-antialiased;text-rendering:optimizeLegibility;',
      axisPointer: { type: 'line', lineStyle: { color: vars.muted, type: 'dashed' } } },
    legend: { type: 'scroll', bottom: 4, left: 12, right: 12, itemWidth: 16, itemHeight: 8, itemGap: 18,
      textStyle: text, pageTextStyle: text, pageIconColor: vars.foreground, pageIconInactiveColor: vars.border },
    grid: { left: 16, right: 24, top: 40, bottom: 64, containLabel: true },
  }
}

export function chartAxis() {
  const vars = themeVars()
  return { axisLabel: { color: vars.muted, fontSize: 11, hideOverlap: true },
    nameTextStyle: { color: vars.muted, fontSize: 11, padding: [0, 0, 8, 0] },
    axisLine: { lineStyle: { color: vars.border } }, axisTick: { show: false },
    splitLine: { lineStyle: { color: vars.border, type: 'dashed', opacity: 0.6 } } }
}
