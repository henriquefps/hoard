import js from '@eslint/js'
import globals from 'globals'
import reactHooks from 'eslint-plugin-react-hooks'
import reactRefresh from 'eslint-plugin-react-refresh'
import tseslint from 'typescript-eslint'
import { defineConfig, globalIgnores } from 'eslint/config'

export default defineConfig([
  globalIgnores(['dist', 'android', 'ios', 'server']),
  {
    files: ['**/*.{ts,tsx}'],
    extends: [
      js.configs.recommended,
      tseslint.configs.recommended,
      reactHooks.configs.flat.recommended,
      reactRefresh.configs.vite,
    ],
    languageOptions: {
      globals: globals.browser,
    },
  },
  {
    // shadcn/ui files export variants (buttonVariants, toggleVariants) next to the
    // component, and the providers export their context hook next to the provider.
    files: [
      'src/components/ui/**/*.{ts,tsx}',
      'src/db/DbProvider.tsx',
      'src/components/ThemeMenu.tsx',
      'src/components/MachineIcon.tsx',
      'src/routes/HomePage.tsx',
      'src/router.tsx',
      'src/components/SortableList.tsx',
      'src/features/machines/AddressEditor.tsx',
      'src/features/machines/actions.tsx',
      'src/features/route/RouteProvider.tsx',
      'src/features/route/RouteStatus.tsx',
      'src/features/transfers/TransfersProvider.tsx',
      'src/features/transfers/TransfersViews.tsx',
    ],
    rules: { 'react-refresh/only-export-components': 'off' },
  },
  {
    // Unused destructured fields are how a rest copy drops keys.
    files: ['**/*.{ts,tsx}'],
    rules: {
      '@typescript-eslint/no-unused-vars': ['error', { argsIgnorePattern: '^_', varsIgnorePattern: '^_', destructuredArrayIgnorePattern: '^_' }],
    },
  },
])
