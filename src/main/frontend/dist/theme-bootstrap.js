(() => {
  try {
    const theme = localStorage.getItem("vinylmatch_theme");
    if (theme === "light" || theme === "dark") document.documentElement.dataset.theme = theme;
  } catch (_) {}

  window.addEventListener("error", (event) => {
    const target = event.target;
    if (target instanceof HTMLImageElement && target.classList.contains("hero-logo")) target.hidden = true;
  }, true);

  document.addEventListener("DOMContentLoaded", () => {
    const labels = {
      "3": "Design 3 - Archive Column",
      "4": "Design 4 - Brutalist Raw",
      "6": "Design 6 - Editorial Magazine",
    };
    const variant = document.documentElement.dataset.homeVariant;
    const label = document.getElementById("variant-label");
    if (label && labels[variant]) label.textContent = labels[variant];
  }, { once: true });
})();
