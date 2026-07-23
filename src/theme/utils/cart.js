import { writable } from 'svelte/store';
import { browser } from '@panomc/sdk/svelte';

/**
 * Client-side shopping basket, persisted to localStorage.
 * Item shape: { productId: number, quantity: number }.
 * SSR-safe: reads/writes are guarded behind `browser`.
 */
const STORAGE_KEY = 'pano-plugin-market-cart';

function readInitial() {
  if (!browser) return [];

  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    if (!raw) return [];

    const parsed = JSON.parse(raw);
    if (!Array.isArray(parsed)) return [];

    return parsed
      .filter(
        (item) =>
          item &&
          typeof item.productId === 'number' &&
          typeof item.quantity === 'number' &&
          item.quantity > 0
      )
      .map((item) => ({ productId: item.productId, quantity: Math.floor(item.quantity) }));
  } catch (e) {
    return [];
  }
}

export const cart = writable(readInitial());

if (browser) {
  cart.subscribe((items) => {
    try {
      localStorage.setItem(STORAGE_KEY, JSON.stringify(items));
    } catch (e) {
      // storage may be unavailable (private mode / quota) — ignore.
    }
  });
}

export function addToCart(productId, quantity = 1) {
  cart.update((items) => {
    const existing = items.find((item) => item.productId === productId);
    if (existing) {
      return items.map((item) =>
        item.productId === productId ? { ...item, quantity: item.quantity + quantity } : item
      );
    }
    return [...items, { productId, quantity }];
  });
}

export function setQuantity(productId, quantity) {
  cart.update((items) => {
    if (quantity <= 0) {
      return items.filter((item) => item.productId !== productId);
    }
    return items.map((item) =>
      item.productId === productId ? { ...item, quantity } : item
    );
  });
}

export function removeFromCart(productId) {
  cart.update((items) => items.filter((item) => item.productId !== productId));
}

export function clearCart() {
  cart.set([]);
}
