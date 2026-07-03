import { useEffect, useState, useCallback } from "react";

export type Lang = "es" | "en";

export const dict = {
  es: {
    nav: {
      status: "01: EN LÍNEA",
      encrypted: "02: CIFRADO",
      cta: "Únete",
    },
    hero: {
      eyebrow: "PROTOCOLO DE EMERGENCIA · v0.1",
      titleA: "Cuando la red",
      titleB: "colapsa,",
      titleC: "tu señal sigue viva.",
      sub: "Red de emergencia BLE-mesh sin conexión. Cada teléfono retransmite un paquete SOS firmado, salto a salto. Sin torres, sin internet, sin servidor.",
      cta: "Únete a la lista",
      scrollHint: "Cómo funciona",
    },
    band: {
      items: [
        { n: "0", l: "torres necesarias" },
        { n: "0", l: "servidores obligatorios" },
        { n: "22 B", l: "por señal, firmada Ed25519" },
        { n: "100%", l: "sin conexión, por diseño" },
      ],
    },
    how: {
      section: "CÓMO FUNCIONA",
      title: "Tu señal salta de mano en mano hasta encontrar ayuda.",
      sub: "Sin infraestructura. Cada teléfono cercano recibe tu auxilio, lo guarda y lo vuelve a emitir. Así el mensaje viaja más allá de tu alcance de radio.",
      steps: [
        { t: "Transmites tu SOS", d: "Pulsas una vez. Tu ubicación, tipo de auxilio y batería se empaquetan en 22 bytes y se firman con tu llave." },
        { t: "Los teléfonos cercanos lo repiten", d: "Cada equipo en alcance lo escucha por Bluetooth, lo verifica y lo vuelve a emitir. Salto a salto, sin que nadie tenga que aceptar nada." },
        { t: "Llega a quien puede ayudar", d: "El auxilio aparece en el radar de cada persona en la cadena. Cuando alguien recupera internet, puede subirlo a las autoridades." },
      ],
      proof: "Cada salto re-verifica la firma Ed25519 antes de retransmitir. Lo falsificado o alterado se descarta en silencio.",
      relay: {
        origin: "origen · TTL 8",
        hop1: "salto 1 · TTL 7",
        hop2: "salto 2 · TTL 6",
        received: "recibido",
      },
    },
    arch: {
      section: "ARQUITECTURA",
      title: "Diseñado para sobrevivir a la infraestructura.",
      a: { k: "CERO DEPENDENCIA", t: "Sin torres, sin internet, sin servidor central. El protocolo vive en los teléfonos.", d: "BLE 4.0+ · Android nativo" },
      b: { k: "PRUEBA CRIPTOGRÁFICA", t: "Cada paquete de 22 bytes lleva una firma Ed25519. No se puede falsificar un SOS.", d: "Ed25519 · 22 B payload" },
      c: { k: "ALCANCE INFINITO", t: "Cada teléfono es un nodo. El mensaje salta hasta que alguien lo escuche.", d: "TTL configurable · dedupe por hash" },
    },
    proof: {
      section: "EVIDENCIA",
      title: "El paquete pesa 22 bytes. Cabe en un latido.",
      sub: "Ejemplo de payload SOS transmitido durante pruebas de campo en Caracas.",
      labels: { hdr: "cabecera", nid: "nodo origen", geo: "coordenadas", ttl: "ttl", sig: "firma ed25519 (truncada)" },
    },
    showcase: {
      section: "APLICACIÓN",
      title: "Una sola pantalla. Un solo botón.",
      sub: "La aplicación Android funciona sin señal, sin cuenta, sin registro. Se enciende, escucha, retransmite.",
      shots: {
        a: "Activación de nodo",
        b: "Emisión de SOS",
        c: "Registro de saltos",
      },
    },
    waitlist: {
      section: "ACCESO ANTICIPADO",
      title: "Sé de los primeros en llevar la red.",
      sub: "Te avisamos cuando la beta abra en tu país. Sin spam. Sin ruido.",
      email: "tu@correo.com",
      country: "País",
      submit: "Solicitar acceso",
      submitting: "Enviando…",
      success: "Estás dentro. Te escribiremos.",
      errorInvalid: "Correo no válido.",
      errorNetwork: "Sin conexión. Intenta de nuevo.",
      errorServer: "Algo falló. Intenta más tarde.",
      privacy: "Solo email y país. Nada más.",
    },
    footer: {
      built: "Construido para Venezuela 🇻🇪 · 2026",
      openSource: "Hackathon",
      contact: "GitHub",
    },
  },
  en: {
    nav: {
      status: "01: ONLINE",
      encrypted: "02: ENCRYPTED",
      cta: "Join",
    },
    hero: {
      eyebrow: "EMERGENCY PROTOCOL · v0.1",
      titleA: "When the network",
      titleB: "collapses,",
      titleC: "your signal stays alive.",
      sub: "Connectionless BLE-mesh emergency network. Every phone relays a signed SOS packet, hop by hop. No towers, no internet, no server.",
      cta: "Join the waitlist",
      scrollHint: "How it works",
    },
    band: {
      items: [
        { n: "0", l: "towers required" },
        { n: "0", l: "mandatory servers" },
        { n: "22 B", l: "per signal, Ed25519-signed" },
        { n: "100%", l: "offline, by design" },
      ],
    },
    how: {
      section: "HOW IT WORKS",
      title: "Your signal hops hand to hand until it finds help.",
      sub: "No infrastructure. Every nearby phone receives your call, stores it, and broadcasts it again. That's how the message travels beyond your own radio range.",
      steps: [
        { t: "You send your SOS", d: "One tap. Your location, type of emergency and battery are packed into 22 bytes and signed with your key." },
        { t: "Nearby phones repeat it", d: "Every device in range hears it over Bluetooth, verifies it, and broadcasts it again. Hop by hop — no one has to accept anything." },
        { t: "It reaches someone who can help", d: "The call shows up on the radar of everyone in the chain. When someone regains internet, they can pass it to the authorities." },
      ],
      proof: "Every hop re-verifies the Ed25519 signature before relaying. Anything forged or altered is dropped silently.",
      relay: {
        origin: "origin · TTL 8",
        hop1: "hop 1 · TTL 7",
        hop2: "hop 2 · TTL 6",
        received: "received",
      },
    },
    arch: {
      section: "ARCHITECTURE",
      title: "Built to outlive the infrastructure.",
      a: { k: "ZERO DEPENDENCY", t: "No towers, no internet, no central server. The protocol lives on the phones.", d: "BLE 4.0+ · native Android" },
      b: { k: "CRYPTOGRAPHIC PROOF", t: "Every 22-byte packet carries an Ed25519 signature. An SOS cannot be forged.", d: "Ed25519 · 22 B payload" },
      c: { k: "INFINITE REACH", t: "Every phone is a node. The message hops until someone hears it.", d: "Configurable TTL · hash dedupe" },
    },
    proof: {
      section: "EVIDENCE",
      title: "The packet weighs 22 bytes. It fits in a heartbeat.",
      sub: "Sample SOS payload captured during field tests in Caracas.",
      labels: { hdr: "header", nid: "origin node", geo: "coordinates", ttl: "ttl", sig: "ed25519 signature (truncated)" },
    },
    showcase: {
      section: "APPLICATION",
      title: "One screen. One button.",
      sub: "The Android app works without signal, without account, without registration. Turn it on — it listens, it relays.",
      shots: {
        a: "Node activation",
        b: "SOS broadcast",
        c: "Hop log",
      },
    },
    waitlist: {
      section: "EARLY ACCESS",
      title: "Be first to carry the network.",
      sub: "We'll ping you when the beta opens in your country. No spam. No noise.",
      email: "you@email.com",
      country: "Country",
      submit: "Request access",
      submitting: "Sending…",
      success: "You're in. We'll write.",
      errorInvalid: "Invalid email.",
      errorNetwork: "Offline. Try again.",
      errorServer: "Something failed. Try later.",
      privacy: "Just email and country. Nothing else.",
    },
    footer: {
      built: "Built for Venezuela 🇻🇪 · 2026",
      openSource: "Hackathon",
      contact: "GitHub",
    },
  },
} as const;

const KEY = "guacamalla:lang";

function detectLang(): Lang {
  if (typeof window === "undefined") return "es";
  const stored = window.localStorage.getItem(KEY) as Lang | null;
  if (stored === "es" || stored === "en") return stored;
  // Default to Spanish for this Venezuela-first product.
  return "es";
}

export function useLanguage() {
  const [lang, setLangState] = useState<Lang>("es");

  useEffect(() => {
    const l = detectLang();
    setLangState(l);
    document.documentElement.lang = l;
  }, []);

  const setLang = useCallback((l: Lang) => {
    setLangState(l);
    if (typeof window !== "undefined") {
      window.localStorage.setItem(KEY, l);
      document.documentElement.lang = l;
    }
  }, []);

  const t = dict[lang];
  return { lang, setLang, t };
}
