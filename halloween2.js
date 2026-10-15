/**
 * Overlay Halloween 100 % JavaScript vanilla (aucun fichier CSS/HTML requis).
 * Usage :
 *   const overlay = createHalloweenOverlay();      // lance l'overlay
 *   overlay.destroy();                             // le retire
 *
 * Options :
 *   duration   : durée d'une traversée de la sorcière en ms (défaut 9000)
 *   darken     : assombrit légèrement la page (défaut true)
 *   closable   : affiche un bouton pour fermer (défaut true)
 *   zIndex     : z-index de l'overlay (défaut 99999)
 */
function createHalloweenOverlay(options = {}) {
  const cfg = Object.assign(
    { duration: 9000, darken: true, closable: true, zIndex: 99999 },
    options
  );
  const reduceMotion = window.matchMedia("(prefers-reduced-motion: reduce)").matches;
  const NS = "hlw-" + Math.random().toString(36).slice(2, 7); // évite les conflits de classes

  // ---------- Styles injectés ----------
  const style = document.createElement("style");
  style.textContent = `
    .${NS}-root{position:fixed;inset:0;pointer-events:none;z-index:${cfg.zIndex};overflow:hidden;
      ${cfg.darken ? "background:radial-gradient(ellipse at 50% 30%,rgba(40,10,60,.05),rgba(20,5,30,.45));" : ""}}
    .${NS}-moon{position:absolute;top:6vh;right:12vw;width:110px;height:110px;border-radius:50%;
      background:#fbeec1;box-shadow:0 0 60px 20px rgba(251,238,193,.35);opacity:.9}
    .${NS}-witch{position:absolute;left:0;top:0;width:190px;will-change:transform}
    .${NS}-pumpkin{position:absolute;right:24px;bottom:16px;width:120px;
      animation:${NS}-bob 3s ease-in-out infinite}
    .${NS}-glow{animation:${NS}-flicker 1.6s infinite}
    .${NS}-close{position:absolute;top:12px;left:12px;pointer-events:auto;cursor:pointer;
      font:600 14px/1 system-ui,sans-serif;color:#fff;background:#2b1840;border:2px solid #ff7a18;
      border-radius:20px;padding:8px 14px}
    .${NS}-close:focus-visible{outline:3px solid #ffd23f;outline-offset:2px}
    @keyframes ${NS}-bob{0%,100%{transform:translateY(0)}50%{transform:translateY(-6px)}}
    @keyframes ${NS}-flicker{0%,100%{opacity:1}20%{opacity:.7}45%{opacity:.95}60%{opacity:.6}80%{opacity:.9}}
    @media (prefers-reduced-motion:reduce){.${NS}-pumpkin,.${NS}-glow{animation:none}}
    @media (max-width:600px){.${NS}-witch{width:120px}.${NS}-pumpkin{width:80px}.${NS}-moon{width:70px;height:70px}}
  `;
  document.head.appendChild(style);

  // ---------- Conteneur ----------
  const root = document.createElement("div");
  root.className = `${NS}-root`;
  root.setAttribute("aria-hidden", "true");

  const moon = document.createElement("div");
  moon.className = `${NS}-moon`;
  root.appendChild(moon);

  // ---------- Sorcière sur son balai (SVG) ----------
  const witch = document.createElement("div");
  witch.className = `${NS}-witch`;
  witch.innerHTML = `
  <svg viewBox="0 0 200 130" xmlns="http://www.w3.org/2000/svg">
    <!-- balai -->
    <line x1="20" y1="92" x2="185" y2="72" stroke="#5a3a1e" stroke-width="5" stroke-linecap="round"/>
    <path d="M28 91 L0 78 L4 88 L-2 96 L6 100 L2 110 L30 97 Z" fill="#c98a2e"/>
    <path d="M26 88 L30 98" stroke="#7a4f1a" stroke-width="3"/>
    <!-- cape qui flotte -->
    <path d="M95 50 Q60 55 45 80 Q70 72 80 84 Q88 70 100 72 Z" fill="#3b1f5c"/>
    <!-- corps -->
    <path d="M92 48 Q110 44 118 60 L122 84 L96 86 Z" fill="#1a0f24"/>
    <!-- jambe + botte -->
    <path d="M108 82 L120 100 L132 100 L130 96 L122 94 L114 80 Z" fill="#1a0f24"/>
    <!-- bras tenant le balai -->
    <path d="M112 56 Q130 64 140 78" stroke="#1a0f24" stroke-width="7" fill="none" stroke-linecap="round"/>
    <circle cx="141" cy="78" r="4.5" fill="#7fbf5a"/>
    <!-- tête -->
    <circle cx="108" cy="40" r="10" fill="#7fbf5a"/>
    <path d="M117 40 L124 43 L116 45 Z" fill="#6aa84a"/>
    <!-- cheveux -->
    <path d="M98 36 Q88 44 84 58 Q94 50 100 46 Z" fill="#ff7a18"/>
    <!-- chapeau -->
    <ellipse cx="106" cy="31" rx="20" ry="4.5" fill="#1a0f24" transform="rotate(-10 106 31)"/>
    <path d="M95 30 L122 26 L88 0 Z" fill="#1a0f24"/>
    <path d="M96 29 L119 25.5 L118 22 L96 25 Z" fill="#8a3fd1"/>
  </svg>`;
  root.appendChild(witch);

  // ---------- Citrouille (SVG) ----------
  const pumpkin = document.createElement("div");
  pumpkin.className = `${NS}-pumpkin`;
  pumpkin.innerHTML = `
  <svg viewBox="0 0 120 110" xmlns="http://www.w3.org/2000/svg">
    <path d="M58 22 Q56 8 66 2 L70 6 Q63 12 64 22 Z" fill="#3f6b2a"/>
    <path d="M64 12 Q80 4 88 12" stroke="#4f8a34" stroke-width="3" fill="none"/>
    <ellipse cx="30" cy="64" rx="26" ry="40" fill="#e0620d"/>
    <ellipse cx="90" cy="64" rx="26" ry="40" fill="#e0620d"/>
    <ellipse cx="46" cy="64" rx="24" ry="42" fill="#f47a1a"/>
    <ellipse cx="74" cy="64" rx="24" ry="42" fill="#f47a1a"/>
    <ellipse cx="60" cy="64" rx="20" ry="43" fill="#ff8c2a"/>
    <g class="${NS}-glow" fill="#ffd23f">
      <path d="M34 50 L46 50 L40 38 Z"/>
      <path d="M74 50 L86 50 L80 38 Z"/>
      <path d="M56 58 L64 58 L60 66 Z"/>
      <path d="M30 74 Q60 100 90 74 L82 78 L78 72 L70 80 L64 74 L56 82 L50 74 L42 80 L38 72 Z"/>
    </g>
  </svg>`;
  root.appendChild(pumpkin);

  // ---------- Bouton fermer ----------
  if (cfg.closable) {
    const btn = document.createElement("button");
    btn.className = `${NS}-close`;
    btn.type = "button";
    btn.textContent = "Fermer l'animation";
    btn.addEventListener("click", () => destroy());
    root.appendChild(btn);
    root.removeAttribute("aria-hidden");
  }

  document.body.appendChild(root);

  // ---------- Animation du vol ----------
  let rafId = null;
  let start = null;

  function fly(ts) {
    if (start === null) start = ts;
    const t = ((ts - start) % cfg.duration) / cfg.duration; // 0 → 1
    const w = window.innerWidth;
    const h = window.innerHeight;
    const size = witch.offsetWidth;
    const x = -size + t * (w + size * 2);
    const y = h * 0.22 + Math.sin(t * Math.PI * 4) * 30 + Math.sin(t * Math.PI) * -40;
    const tilt = Math.cos(t * Math.PI * 4) * -6;
    witch.style.transform = `translate(${x}px, ${y}px) rotate(${tilt}deg)`;
    rafId = requestAnimationFrame(fly);
  }

  if (reduceMotion) {
    // Position fixe si l'utilisateur préfère réduire les animations
    witch.style.transform = `translate(${window.innerWidth * 0.15}px, ${window.innerHeight * 0.18}px)`;
  } else {
    rafId = requestAnimationFrame(fly);
  }

  // ---------- Nettoyage ----------
  function destroy() {
    if (rafId) cancelAnimationFrame(rafId);
    root.remove();
    style.remove();
  }

  return { destroy, element: root };
}

// Lancement automatique (à supprimer si vous préférez l'appeler vous-même)
if (document.readyState === "loading") {
  document.addEventListener("DOMContentLoaded", () => createHalloweenOverlay());
} else {
  createHalloweenOverlay();
}
