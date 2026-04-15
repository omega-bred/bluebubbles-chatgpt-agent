export function displayModelLabel(label?: string | null) {
  if (!label) {
    return "Free";
  }
  const normalized = label.trim().toLowerCase();
  if (normalized === "local" || normalized === "model local") {
    return "Free";
  }
  return label;
}
