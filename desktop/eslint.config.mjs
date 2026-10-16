import js from '@eslint/js';
import { defineConfig } from 'eslint/config';
import tseslint from 'typescript-eslint';

export default defineConfig([
  { ignores: ['dist/**', 'release/**', 'staging/**', 'node_modules/**'] },
  {
    files: ['scripts/**/*.cjs', 'test/**/*.cjs', 'e2e/**/*.cjs'],
    extends: [js.configs.recommended],
    languageOptions: {
      sourceType: 'commonjs',
      globals: { require: 'readonly', module: 'readonly', process: 'readonly', console: 'readonly', Buffer: 'readonly', __dirname: 'readonly', __filename: 'readonly', setTimeout: 'readonly', URL: 'readonly' }
    }
  },
  {
    files: ['src/**/*.ts'],
    extends: [js.configs.recommended, ...tseslint.configs.recommended]
  }
]);
