# Instructions

- Following Playwright test failed.
- Explain why, be concise, respect Playwright best practices.
- Provide a snippet of code with the fix, if possible.

# Test info

- Name: podmix.spec.ts >> les catégories héritées sont corrigées sans contaminer les prochains ajouts
- Location: tests/e2e/podmix.spec.ts:147:1

# Error details

```
Error: locator.click: Target page, context or browser has been closed
Call log:
  - waiting for getByRole('button', { name: 'Bibliothèque' }).last()

```

```
Error: write EPIPE
```