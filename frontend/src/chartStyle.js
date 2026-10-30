import * as echarts from 'echarts'

// SVG keeps chart labels crisp at browser zoom levels and on high-DPI displays.
export function createChart(element) {
  const chart = echarts.init(element, null, { renderer: 'svg' })
  const observer = new ResizeObserver(() => { if (!chart.isDisposed()) chart.resize() })
  observer.observe(element)
  const dispose = chart.dispose.bind(chart)
  chart.dispose = () => { observer.disconnect(); dispose() }
  return chart
}

export function chartStyle() {
  const css = getComputedStyle(document.documentElement)
  const color = name => css.getPropertyValue(name).trim()
  const fontFamily = getComputedStyle(document.body).fontFamily
  const text = { fontFamily, fontSize: 12, color: color('--muted-foreground') }
  return {
    animation: false,
    backgroundColor: 'transparent',
    color: ['--info', '--success', '--warning', '--purple', '--danger'].map(color),
    textStyle: { ...text, color: color('--foreground') },
    tooltip: { trigger: 'axis', renderMode: 'html', confine: true,
      backgroundColor: color('--card'), borderColor: color('--border'), padding: [10, 12],
      textStyle: { ...text, color: color('--foreground') },
      extraCssText: 'border-radius:6px;box-shadow:none;line-height:1.7;',
      axisPointer: { type: 'line', lineStyle: { color: color('--muted-foreground'), type: 'dashed' } } },
    legend: { type: 'scroll', bottom: 4, left: 12, right: 12, itemWidth: 16, itemHeight: 8, itemGap: 18,
      textStyle: text, pageTextStyle: text, pageIconColor: color('--foreground'), pageIconInactiveColor: color('--border') },
    grid: { left: 16, right: 24, top: 40, bottom: 64, containLabel: true },
  }
}

export function chartAxis() {
  const css = getComputedStyle(document.documentElement)
  const muted = css.getPropertyValue('--muted-foreground').trim()
  const border = css.getPropertyValue('--border').trim()
  return { axisLabel: { color: muted, fontSize: 11, hideOverlap: true },
    nameTextStyle: { color: muted, fontSize: 11, padding: [0, 0, 8, 0] },
    axisLine: { lineStyle: { color: border } }, axisTick: { show: false },
    splitLine: { lineStyle: { color: border, type: 'dashed', opacity: 0.6 } } }
}
