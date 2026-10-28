// ESLint del frontend (U5; propuesta UI-17): reglas de los hooks de React y de accesibilidad (jsx-a11y) sobre TypeScript. tsc ya
// revisa los tipos y los imports sin usar; aquí van las reglas que tsc no ve.
import js from '@eslint/js';
import jsxA11y from 'eslint-plugin-jsx-a11y';
import reactHooks from 'eslint-plugin-react-hooks';
import globals from 'globals';
import tseslint from 'typescript-eslint';

export default tseslint.config(
  { ignores: ['dist', 'node_modules'] },
  {
    files: ['src/**/*.{ts,tsx}'],
    extends: [js.configs.recommended, ...tseslint.configs.recommended, jsxA11y.flatConfigs.recommended],
    languageOptions: { ecmaVersion: 2022, globals: globals.browser },
    plugins: { 'react-hooks': reactHooks },
    rules: {
      'react-hooks/rules-of-hooks': 'error',
      'react-hooks/exhaustive-deps': 'warn',
      // tsc (noUnusedLocals, noUnusedParameters) ya lo revisa
      '@typescript-eslint/no-unused-vars': 'off',
    },
  },
);
