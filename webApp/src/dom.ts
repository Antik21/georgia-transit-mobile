export function element<K extends keyof HTMLElementTagNameMap>(tag: K, className: string, text?: string): HTMLElementTagNameMap[K] {
  const result = document.createElement(tag);
  if (className) result.className = className;
  if (text) result.textContent = text;
  return result;
}

export function button(className: string, text: string, onClick: () => void, testId?: string) {
  const result = element("button", className, text);
  result.type = "button";
  if (testId) result.dataset.testid = testId;
  result.addEventListener("click", onClick);
  return result;
}

export function icon(className: string) {
  const result = element("span", `icon ${className}`);
  result.setAttribute("aria-hidden", "true");
  return result;
}

export function heading(text: string) {
  const result = element("h1", "screen-heading", text);
  result.tabIndex = -1;
  result.dataset.focusKey = "heading";
  return result;
}

export function replacePreservingFocus(container: HTMLElement, children: HTMLElement[]) {
  const active = document.activeElement;
  const focusKey = active instanceof HTMLElement && container.contains(active) ? active.dataset.focusKey : undefined;
  container.replaceChildren(...children);
  if (!focusKey) return;
  const focused = Array.from(container.querySelectorAll<HTMLElement>("[data-focus-key]"))
    .find((item) => item.dataset.focusKey === focusKey);
  focused?.focus({ preventScroll: true });
}

export function status(message: string, loading = false) {
  const result = element("p", "status-message");
  result.setAttribute("role", "status");
  if (loading) result.append(icon("spinner"));
  result.append(document.createTextNode(message));
  return result;
}

export function applyRouteColors(badge: HTMLElement, background: string, foreground: string) {
  if (/^#[0-9a-f]{6}$/i.test(background) && /^#[0-9a-f]{6}$/i.test(foreground)) {
    badge.style.backgroundColor = background;
    badge.style.color = foreground;
  }
}
