export function BlurText({ children, by = "char" }: { children: string; by?: "word" | "char" }) {
  const parts = by === "word" ? children.split(/(\s+)/) : Array.from(children);
  const staggerMs = Math.min(35, 150 / Math.max(1, parts.length - 1));

  return (
    <span>
      <span className="sr-only">{children}</span>
      <span aria-hidden="true">
        {parts.map((part, index) => (
          <span
            key={index}
            className="inline-block whitespace-pre motion-safe:animate-in motion-safe:fade-in motion-safe:blur-in-xs motion-safe:slide-in-from-bottom-1 motion-safe:duration-300 motion-safe:fill-mode-backwards"
            style={{ animationDelay: `${index * staggerMs}ms` }}
          >
            {part}
          </span>
        ))}
      </span>
    </span>
  );
}
