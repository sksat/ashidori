/// <reference types="vite/client" />

/** Path of the copied MapLibre build, e.g. "/vendor/maplibre-gl-6.11.2/" (vite.config.ts). */
declare const __MAPLIBRE_PATH__: string;

/** The offline basemap archive the app pins (app/src/main/assets/basemap-pin.json, vite.config.ts). */
declare const __BASEMAP_PIN__: { version: string; file: string; bytes: number; sha256: string };
