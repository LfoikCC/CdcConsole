export function mapByName(sourceColumns, targetColumns) {
  const targets = new Set(targetColumns)
  return Object.fromEntries(sourceColumns.filter(column => targets.has(column)).map(column => [column, column]))
}

export function mapByOrder(sourceColumns, targetColumns) {
  return Object.fromEntries(sourceColumns.slice(0, targetColumns.length).map((column, index) => [column, targetColumns[index]]))
}

export function validMapping(mapping, sourceColumns, targetColumns) {
  const sources = new Set(sourceColumns), targets = new Set(targetColumns)
  const values = Object.values(mapping)
  return Object.entries(mapping).every(([from, to]) => sources.has(from) && targets.has(to))
    && new Set(values).size === values.length
}
