import nextVitals from "eslint-config-next/core-web-vitals";
import nextTypescript from "eslint-config-next/typescript";

const eslintConfig = [
  { ignores: [".wrangler/**", "playwright-report/**", "test-results/**", "backend/target/**"] },
  ...nextVitals,
  ...nextTypescript
];

export default eslintConfig;
