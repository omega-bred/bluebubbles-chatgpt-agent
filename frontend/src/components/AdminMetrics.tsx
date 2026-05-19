const numberFormat = new Intl.NumberFormat();

export function MetricTile({ label, value }: { label: string; value: string }) {
  return (
    <article className="metric-tile">
      <span>{label}</span>
      <strong>{value}</strong>
    </article>
  );
}

export function formatCount(value: number | undefined): string {
  return numberFormat.format(value || 0);
}
