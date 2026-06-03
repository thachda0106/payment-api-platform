// libs/archtest/nodejs/.dependency-cruiser.js
// Module boundary rules for Node.js platform-libs.
// Enforced by: npx depcruise --config this-file.js src/

/** @type {import('dependency-cruiser').IConfiguration} */
module.exports = {
  forbidden: [
    // ─── Libs internal boundaries ───
    // ALL packages can import config. No cross-imports between packages.
    {
      name: 'telemetry-independence',
      severity: 'error',
      from: { path: '^src/telemetry' },
      to: {
        path: '^src/(health|metrics|errors|lifecycle)',
        pathNot: '^src/config',
      },
    },
    {
      name: 'health-independence',
      severity: 'error',
      from: { path: '^src/health' },
      to: {
        path: '^src/(telemetry|metrics|errors|lifecycle)',
        pathNot: '^src/config',
      },
    },
    {
      name: 'config-independence',
      severity: 'error',
      from: { path: '^src/config' },
      to: {
        path: '^src/(telemetry|health|metrics|errors|lifecycle)',
      },
    },
    // ─── Service boundaries ───
    {
      name: 'no-cross-service-imports',
      severity: 'error',
      from: { path: '^services/' },
      to: {
        path: '^services/',
        pathNot: '^$from',
      },
    },
  ],
  options: {
    doNotFollow: {
      path: 'node_modules',
    },
    moduleSystems: ['cjs', 'es6'],
    tsPreCompilationDeps: true,
    combinedDependencies: true,
  },
};
