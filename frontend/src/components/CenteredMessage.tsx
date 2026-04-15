export function CenteredMessage({ title, body }: { title: string; body: string }) {
  return (
    <div className="centered-message">
      <h1>{title}</h1>
      <p>{body}</p>
    </div>
  );
}
